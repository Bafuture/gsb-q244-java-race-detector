package com.example.gsb.racedetector;

/** Category of synchronization that could order two accesses. */
public enum SyncCategory {
    LOCK,
    VOLATILE,
    THREAD_LIFECYCLE
}
