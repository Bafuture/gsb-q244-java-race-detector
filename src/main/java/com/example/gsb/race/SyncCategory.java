package com.example.gsb.race;

/**
 * High-level synchronization mechanisms that, when used correctly,
 * can order two accesses on the same memory location.
 */
public enum SyncCategory {
    /** Both accesses executing while holding a common monitor. */
    LOCK,
    /** A volatile write observed by a subsequent volatile read. */
    VOLATILE,
    /** One thread was started by, or joined by, the other. */
    THREAD_LIFECYCLE
}
