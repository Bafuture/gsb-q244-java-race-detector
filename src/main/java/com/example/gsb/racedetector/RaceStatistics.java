package com.example.gsb.racedetector;

/**
 * Detector statistics.
 *
 * @param registeredAccessCount    number of accesses kept (after sampling)
 * @param registeredSyncEventCount number of registered synchronization events
 * @param comparisonCount          number of access pairs subjected to a
 *                                 happens-before (vector clock) comparison
 * @param prunedPairCount          access pairs excluded from race candidacy:
 *                                 different location, same thread, read/read,
 *                                 or ordered by happens-before
 * @param sampledOutCount          accesses dropped by the sampling policy
 */
public record RaceStatistics(
        long registeredAccessCount,
        long registeredSyncEventCount,
        long comparisonCount,
        long prunedPairCount,
        long sampledOutCount
) {
}
