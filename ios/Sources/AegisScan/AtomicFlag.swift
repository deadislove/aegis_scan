// Copyright 2026 AegisScan Authors. All rights reserved.

import os

/// Lock-free-in-practice boolean gate built on `os_unfair_lock` (heap-pinned,
/// so the lock never moves, which Swift would otherwise allow for a stored
/// struct). Used as the frame back-pressure flag: `tryAcquire()` either takes
/// the gate in O(1) or tells the caller to drop the frame. It never blocks
/// waiting for inference.
///
/// Thread Sanitizer clean: every read and write happens under the unfair lock,
/// which gives acquire/release semantics.
final class AtomicFlag: @unchecked Sendable {
    private let lock: UnsafeMutablePointer<os_unfair_lock>
    private var value = false

    init() {
        lock = .allocate(capacity: 1)
        lock.initialize(to: os_unfair_lock())
    }

    deinit {
        lock.deinitialize(count: 1)
        lock.deallocate()
    }

    /// Atomically sets the flag if it was clear.
    /// - Returns: `true` if the caller now owns the gate, `false` if busy.
    func tryAcquire() -> Bool {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        if value { return false }
        value = true
        return true
    }

    /// Clears the flag (idempotent).
    func release() {
        os_unfair_lock_lock(lock)
        value = false
        os_unfair_lock_unlock(lock)
    }

    /// Current value (diagnostics only).
    var isSet: Bool {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        return value
    }
}

/// Monotonic counter guarded by `os_unfair_lock` (frame statistics).
final class AtomicCounter: @unchecked Sendable {
    private let lock: UnsafeMutablePointer<os_unfair_lock>
    private var value: UInt64 = 0

    init() {
        lock = .allocate(capacity: 1)
        lock.initialize(to: os_unfair_lock())
    }

    deinit {
        lock.deinitialize(count: 1)
        lock.deallocate()
    }

    func increment() {
        os_unfair_lock_lock(lock)
        value &+= 1
        os_unfair_lock_unlock(lock)
    }

    var current: UInt64 {
        os_unfair_lock_lock(lock)
        defer { os_unfair_lock_unlock(lock) }
        return value
    }
}
