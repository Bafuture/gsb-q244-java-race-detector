package com.example.gsb.racedetector;

import java.util.EnumSet;
import java.util.Set;

/**
 * A detected data race: two unordered accesses to the same location, at least
 * one of which is a write.
 *
 * @param location            contested memory location
 * @param first               first access of the racing pair
 * @param second              second access of the racing pair
 * @param missingSynchronization synchronization categories absent between the
 *                              two accessing threads
 */
public record RaceReport(
        String location,
        MemoryAccess first,
        MemoryAccess second,
        Set<SyncCategory> missingSynchronization
) {
    public RaceReport {
        missingSynchronization = Set.copyOf(EnumSet.copyOf(missingSynchronization));
    }
}
