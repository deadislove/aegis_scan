// Copyright 2026 AegisScan Authors. All rights reserved.

import CoreVideo
import XCTest
@testable import AegisScan

final class PipelineDefenseTests: XCTestCase {

    private let f420 = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange

    private func makeBuffer(width: Int, height: Int, format: OSType,
                            luma: UInt8 = 128) -> CVPixelBuffer? {
        var buffer: CVPixelBuffer?
        let attrs = [kCVPixelBufferIOSurfacePropertiesKey as String: [:]] as CFDictionary
        guard CVPixelBufferCreate(nil, width, height, format, attrs, &buffer) == kCVReturnSuccess,
              let buffer else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        if CVPixelBufferIsPlanar(buffer) {
            for plane in 0..<CVPixelBufferGetPlaneCount(buffer) {
                memset(CVPixelBufferGetBaseAddressOfPlane(buffer, plane), Int32(luma),
                       CVPixelBufferGetBytesPerRowOfPlane(buffer, plane)
                           * CVPixelBufferGetHeightOfPlane(buffer, plane))
            }
        } else {
            memset(CVPixelBufferGetBaseAddress(buffer), Int32(luma),
                   CVPixelBufferGetDataSize(buffer))
        }
        CVPixelBufferUnlockBaseAddress(buffer, [])
        return buffer
    }

    func testRejectsUnsupportedFormatAspectRatioAndExposure() throws {
        let bgra = try XCTUnwrap(makeBuffer(width: 640, height: 480,
                                            format: kCVPixelFormatType_32BGRA))
        XCTAssertEqual(CameraScannerPipeline.validate(bgra), .unsupportedPixelFormat)
        let wide = try XCTUnwrap(makeBuffer(width: 4000, height: 200, format: f420))
        XCTAssertEqual(CameraScannerPipeline.validate(wide), .unsupportedAspectRatio)
        let dark = try XCTUnwrap(makeBuffer(width: 640, height: 480, format: f420, luma: 2))
        XCTAssertEqual(CameraScannerPipeline.validate(dark), .tooDark)
        let ok = try XCTUnwrap(makeBuffer(width: 1920, height: 1080, format: f420))
        XCTAssertNil(CameraScannerPipeline.validate(ok))
    }

    func testBackPressureDropsFramesWhileBusy() throws {
        let pipeline = CameraScannerPipeline()
        pipeline.markRunningForTesting()
        let buffer = try XCTUnwrap(makeBuffer(width: 1920, height: 1080, format: f420))

        // Simulate an in-flight frame: the gate is taken.
        XCTAssertTrue(pipeline.isProcessingFrame.tryAcquire())
        for _ in 0..<10 {
            pipeline.processingQueue.sync { pipeline.process(pixelBuffer: buffer) }
        }
        XCTAssertEqual(pipeline.statistics.framesDropped, 10)
        XCTAssertEqual(pipeline.statistics.framesAnalyzed, 0)
        pipeline.isProcessingFrame.release()

        // Gate free: the frame is analyzed and the gate is released afterwards.
        pipeline.processingQueue.async { pipeline.process(pixelBuffer: buffer) }
        let deadline = Date().addingTimeInterval(15)
        while pipeline.statistics.framesAnalyzed == 0, Date() < deadline { usleep(2_000) }
        pipeline.processingQueue.sync {}
        XCTAssertEqual(pipeline.statistics.framesAnalyzed, 1)
        XCTAssertFalse(pipeline.isProcessingFrame.isSet)
    }

    func testConcurrentGateIsExclusive() {
        let flag = AtomicFlag()
        let winners = AtomicCounter()
        DispatchQueue.concurrentPerform(iterations: 64) { _ in
            if flag.tryAcquire() { winners.increment() }
        }
        XCTAssertEqual(winners.current, 1)
    }

    func testCardRequiresConsecutiveFramesAndIsWipedAfterCallback() {
        let pipeline = CameraScannerPipeline()
        var delivered: [String] = []
        var captured: SecurePAN?
        pipeline.onCardDetected = { pan in
            delivered.append(pan.last4)
            XCTAssertEqual(pan.length, 16)  // Readable inside the callback.
            captured = pan
        }
        var config = ScannerConfiguration()
        config.requiredConsecutiveCardHits = 2
        let line = [DetectedText(text: "4111 1111 1111 1111", boundingBox: .zero)]

        pipeline.processingQueue.sync { pipeline.processCards(line, config: config) }
        drainMain()
        XCTAssertTrue(delivered.isEmpty)

        pipeline.processingQueue.sync { pipeline.processCards(line, config: config) }
        drainMain()
        XCTAssertEqual(delivered, ["1111"])
        XCTAssertEqual(captured?.isWiped, true)  // Purged after the callback.
    }

    private func drainMain() {
        let e = expectation(description: "main")
        DispatchQueue.main.async { e.fulfill() }
        wait(for: [e], timeout: 2)
    }
}
