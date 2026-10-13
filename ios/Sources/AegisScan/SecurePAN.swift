// Copyright 2026 AegisScan Authors. All rights reserved.

import CAegisCore
import Foundation

/// Card scheme detected from the PAN's IIN/BIN range.
public enum CardBrand: Int32, Sendable {
    case unknown = 0, visa, mastercard, amex, jcb
}

/// A parsed MM/YY (or MM/YYYY) expiry, as printed on the card.
public struct ExpiryDate: Sendable, Equatable {
    public let month: Int  // 1-12
    public let year: Int   // 4-digit, e.g. 2029
}

/// A PAN held in a wipeable, fixed-size, heap-pinned buffer.
///
/// PCI-DSS design:
/// - The digits live in a manually allocated buffer (never a Swift `String`,
///   which could be copied or interned), so `wipe()` reliably clears the only
///   copy.
/// - `description` / `debugDescription` are redacted, so `print(pan)`,
///   string interpolation, and crash logs never reveal digits.
/// - Not `Codable`: it can't be written to `UserDefaults` or disk by accident.
/// - The digits are zeroed by `wipe()`; the scanner calls it right after your
///   callback returns, and `deinit` calls it as a safety net.
public final class SecurePAN: @unchecked Sendable {
    private let lock = NSLock()
    private let storage: UnsafeMutablePointer<UInt8>
    private let capacity = Int(AEGIS_PAN_BUFFER_SIZE)
    private var count: Int

    /// Card scheme.
    public let brand: CardBrand
    /// Last four digits (safe to display under PCI-DSS 3.4 truncation rules).
    public let last4: String
    /// Best-effort expiry, extracted from the same OCR text as the PAN.
    /// `nil` when no MM/YY pattern was found -- see `FintechCore.extractExpiry`
    /// for the accuracy caveat (no checksum to validate against, unlike the PAN).
    public let expiry: ExpiryDate?
    /// Best-effort cardholder name, extracted from the same OCR text as the
    /// PAN. Markedly less reliable than `expiry` or the PAN itself -- see
    /// `FintechCore.extractCardholderName`. Not PCI-DSS sensitive on its own
    /// (a name alone isn't cardholder data), so it's a plain `String`, not
    /// wiped alongside the PAN digits.
    public let cardholderName: String?

    init(digits: UnsafeBufferPointer<CChar>, count: Int, brand: CardBrand,
         expiry: ExpiryDate? = nil, cardholderName: String? = nil) {
        storage = UnsafeMutablePointer<UInt8>.allocate(capacity: capacity)
        storage.initialize(repeating: 0, count: capacity)
        let n = min(count, capacity - 1, digits.count)
        for i in 0..<n { storage[i] = UInt8(bitPattern: digits[i]) }
        self.count = n
        self.brand = brand
        self.expiry = expiry
        self.cardholderName = cardholderName
        last4 = n >= 4
            ? String(decoding: UnsafeBufferPointer(start: storage + (n - 4), count: 4),
                     as: UTF8.self)
            : ""
    }

    deinit {
        wipe()
        storage.deallocate()
    }

    /// Number of digits (0 after `wipe()`).
    public var length: Int {
        lock.lock(); defer { lock.unlock() }
        return count
    }

    /// `true` once the digits have been wiped.
    public var isWiped: Bool { length == 0 }

    /// Gives scoped access to the ASCII digits. Hand them straight to your
    /// tokenizer or encryptor; do not convert them to `String` or keep them.
    public func withDigits<R>(_ body: (UnsafeBufferPointer<UInt8>) throws -> R) rethrows -> R {
        lock.lock(); defer { lock.unlock() }
        return try body(UnsafeBufferPointer(start: storage, count: count))
    }

    /// Zeroes the digits in place (volatile wipe through the native core).
    public func wipe() {
        lock.lock(); defer { lock.unlock() }
        aegis_secure_zero(storage, Int64(capacity))
        count = 0
    }
}

extension SecurePAN: CustomStringConvertible, CustomDebugStringConvertible,
                     CustomReflectable {
    public var description: String { "SecurePAN(\(brand), ****\(last4))" }
    public var debugDescription: String { description }
    /// Keeps `dump()` / the debugger's mirror from exposing the storage.
    public var customMirror: Mirror {
        Mirror(self, children: ["brand": brand, "last4": last4])
    }
}
