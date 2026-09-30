package com.example.gsb.race;

/**
 * Synchronization events that can establish happens-before edges.
 */
public enum SyncEventType {
    LOCK_ACQUIRE,
    LOCK_RELEASE,
    VOLATILE_READ,
    VOLATILE_WRITE,
    /** A parent thread starts a child thread. */
    THREAD_START,
    /** A thread joins (waits for the end of) another thread. */
    THREAD_JOIN
}
