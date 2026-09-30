package com.example.gsb.racedetector;

/** Kind of a synchronization event used to establish happens-before edges. */
public enum SyncEventType {
    LOCK_ACQUIRE,
    LOCK_RELEASE,
    VOLATILE_READ,
    VOLATILE_WRITE,
    THREAD_START,
    THREAD_JOIN
}
