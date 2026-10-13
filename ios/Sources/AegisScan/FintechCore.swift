// Copyright 2026 AegisScan Authors. All rights reserved.

import CAegisCore
import CoreGraphics
import Foundation

/// A detected barcode. `boundingBox` is in image pixel coordinates with a
/// top-left origin (y grows downwards).
public struct DetectedBarcode: Sendable, Equatable {
    public let payload: String
    public let symbology: String
    public let boundingBox: CGRect

    public init(payload: String, symbology: String, boundingBox: CGRect) {
        self.payload = payload
        self.symbology = symbology
        self.boundingBox = boundingBox
    }
}

/// A recognized text line (same coordinate space as `DetectedBarcode`).
public struct DetectedText: Sendable, Equatable {
    public let text: String
    public let boundingBox: CGRect

    public init(text: String, boundingBox: CGRect) {
        self.text = text
        self.boundingBox = boundingBox
    }
}

/// A barcode associated with its human-readable label.
public struct VoucherMatch: Sendable, Equatable {
    public let barcode: DetectedBarcode
    public let associatedText: String
    /// Center distance in normalized frame coordinates (0...~1.41).
    public let normalizedDistance: Float
}

/// Swift facade over the C++20 core (`fintech_core.hpp` via its C ABI).
public enum FintechCore {
    /// Version of the native core.
    public static var version: String { String(cString: aegis_version()) }

    /// Standard Luhn Mod-10 check (digits only).
    public static func validateLuhn(_ digits: String) -> Bool {
        var copy = digits
        return copy.withUTF8 { buf in
            buf.withMemoryRebound(to: CChar.self) {
                aegis_luhn_validate($0.baseAddress, Int32(clamping: $0.count)) == 1
            }
        }
    }

    /// Repairs OCR confusions (O→0, B→8, l→1, S→5, …), extracts a 13–19 digit
    /// candidate with a supported BIN, and validates it with Luhn.
    /// The native output buffer is wiped before this function returns.
    public static func extractPAN(from rawOCR: String) -> SecurePAN? {
        var text = rawOCR
        return text.withUTF8 { utf8 -> SecurePAN? in
            withUnsafeTemporaryAllocation(of: CChar.self,
                                          capacity: Int(AEGIS_PAN_BUFFER_SIZE)) { out in
                defer { aegis_secure_zero(out.baseAddress, Int64(out.count)) }
                var length: Int32 = 0
                var brand: Int32 = 0
                let status = utf8.withMemoryRebound(to: CChar.self) { input in
                    aegis_extract_pan(input.baseAddress, Int32(clamping: input.count),
                                      out.baseAddress, Int32(out.count),
                                      &length, &brand)
                }
                guard status == AEGIS_OK, length > 0 else { return nil }
                return SecurePAN(digits: UnsafeBufferPointer(out), count: Int(length),
                                 brand: CardBrand(rawValue: brand) ?? .unknown)
            }
        }
    }

    /// Scans for the first MM/YY or MM/YYYY pattern in raw OCR text. Unlike
    /// `extractPAN`, there's no checksum to validate an expiry date against --
    /// a transaction reference or address elsewhere in the frame that happens
    /// to look like "12/29" can't be distinguished from the real expiry. The
    /// month range check (01-12) is the only guard against an obviously-wrong
    /// match.
    public static func extractExpiry(from rawOCR: String) -> ExpiryDate? {
        var text = rawOCR
        return text.withUTF8 { utf8 -> ExpiryDate? in
            var expiry = aegis_expiry()
            let status = utf8.withMemoryRebound(to: CChar.self) { input in
                aegis_extract_expiry(input.baseAddress, Int32(clamping: input.count), &expiry)
            }
            guard status == AEGIS_OK else { return nil }
            return ExpiryDate(month: Int(expiry.month), year: Int(expiry.year))
        }
    }

    /// Best-effort cardholder name: the longest line in the raw OCR text that
    /// looks like a printed name (all uppercase letters/spaces, 2+ words, no
    /// known bank/network word). Markedly less reliable than `extractPAN` or
    /// `extractExpiry` -- there's no structural signal to validate against,
    /// only "looks like a name and isn't a known non-name word." A name alone
    /// isn't PCI-DSS cardholder data, so this returns a plain `String`.
    public static func extractCardholderName(from rawOCR: String) -> String? {
        var text = rawOCR
        return text.withUTF8 { utf8 -> String? in
            withUnsafeTemporaryAllocation(of: CChar.self,
                                          capacity: Int(AEGIS_NAME_BUFFER_SIZE)) { out in
                var length: Int32 = 0
                let status = utf8.withMemoryRebound(to: CChar.self) { input in
                    aegis_extract_cardholder_name(input.baseAddress, Int32(clamping: input.count),
                                                  out.baseAddress, Int32(out.count), &length)
                }
                guard status == AEGIS_OK, length > 0, let base = out.baseAddress else { return nil }
                return base.withMemoryRebound(to: UInt8.self, capacity: Int(length)) { bytes in
                    String(decoding: UnsafeBufferPointer(start: bytes, count: Int(length)), as: UTF8.self)
                }
            }
        }
    }

    /// PAN + expiry + cardholder name from one OCR pass, used by
    /// `CameraScannerPipeline` (which already has the joined text and would
    /// otherwise have to re-scan it three times). `extractPAN` stays PAN-only
    /// for direct callers that don't want the extra scans.
    static func extractCardDetails(from rawOCR: String) -> SecurePAN? {
        guard let pan = extractPAN(from: rawOCR) else { return nil }
        let expiry = extractExpiry(from: rawOCR)
        let name = extractCardholderName(from: rawOCR)
        guard expiry != nil || name != nil else { return pan }
        defer { pan.wipe() }
        return pan.withDigits { digits in
            digits.withMemoryRebound(to: CChar.self) { chars in
                SecurePAN(digits: chars, count: chars.count, brand: pan.brand,
                         expiry: expiry, cardholderName: name)
            }
        }
    }
}

/// Owns a native spatial-matcher handle and its reusable scratch memory.
///
/// Thread-safety: an instance is **not** safe for concurrent use. The scanner
/// keeps one per processing queue, which is always serial.
public final class VoucherMatcher {
    private let handle: OpaquePointer

    /// - Parameter frameSize: normalization frame; `.zero` means "use the
    ///   union of all boxes".
    public init?(frameSize: CGSize = .zero) {
        guard let h = aegis_matcher_create(Float(frameSize.width),
                                           Float(frameSize.height)) else { return nil }
        handle = h
    }

    deinit { aegis_matcher_destroy(handle) }

    /// One-to-one association (vertical stacking preferred, payload overlap
    /// required).
    public func match(barcodes: [DetectedBarcode], texts: [DetectedText]) -> [VoucherMatch] {
        guard !barcodes.isEmpty, !texts.isEmpty else { return [] }

        // Copy every string once into a single contiguous UTF-8 arena so all
        // native pointers stay valid for the duration of the call.
        var arena = [UInt8]()
        var spans = [(offset: Int, length: Int)]()
        spans.reserveCapacity(barcodes.count + texts.count)
        for s in barcodes.map(\.payload) + texts.map(\.text) {
            let utf8 = Array(s.utf8.prefix(4096))
            spans.append((arena.count, utf8.count))
            arena.append(contentsOf: utf8)
        }
        let boxes = barcodes.map(\.boundingBox) + texts.map(\.boundingBox)
        var output = [aegis_match](repeating: aegis_match(),
                                   count: min(barcodes.count, texts.count))
        var count: Int32 = 0

        let status: Int32 = arena.withUnsafeBufferPointer { raw in
            raw.withMemoryRebound(to: CChar.self) { base in
                let items: [aegis_item] = zip(spans, boxes).map { span, box in
                    aegis_item(text: base.baseAddress.map { $0 + span.offset },
                               text_len: Int32(span.length),
                               box: aegis_box(x: Float(box.minX), y: Float(box.minY),
                                              width: Float(box.width),
                                              height: Float(box.height)))
                }
                return items.withUnsafeBufferPointer { all in
                    output.withUnsafeMutableBufferPointer { out in
                        aegis_matcher_match(handle,
                                            all.baseAddress, Int32(barcodes.count),
                                            all.baseAddress.map { $0 + barcodes.count },
                                            Int32(texts.count),
                                            out.baseAddress, Int32(out.count), &count)
                    }
                }
            }
        }
        guard status == AEGIS_OK || status == AEGIS_ERR_BUFFER_TOO_SMALL else { return [] }
        return output.prefix(Int(count)).map { m in
            VoucherMatch(barcode: barcodes[Int(m.barcode_index)],
                         associatedText: texts[Int(m.text_index)].text,
                         normalizedDistance: m.normalized_distance)
        }
    }
}
