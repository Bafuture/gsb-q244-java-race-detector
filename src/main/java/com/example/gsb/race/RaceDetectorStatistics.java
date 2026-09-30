package com.example.gsb.race;

/**
 * Immutable snapshot of detector counters.
 *
 * @param registeredAccesses   total registered memory accesses
 * @param registeredSyncEvents total registered synchronization events
 * @param comparisons          pairwise happens-before checks performed
 * @param prunedPairs          access pairs discarded because they were ordered
 */
public record RaceDetectorStatistics(
        long registeredAccesses,
        long registeredSyncEvents,
        long comparisons,
        long prunedPairs) {
}
