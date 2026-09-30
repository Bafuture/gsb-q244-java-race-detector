package com.example.gsb.race;

import java.util.Set;

/**
 * A detected data race: two accesses to the same location, at least one of
 * them a write, with no happens-before edge between them.
 *
 * @param location               the raced memory location
 * @param first                  the earlier-registered access
 * @param second                 the later-registered access
 * @param missingSynchronization synchronization categories that did not
 *                               order the pair and whose correct use would
 *                               have prevented the race
 */
public record Race(
        String location,
        MemoryAccess first,
        MemoryAccess second,
        Set<SyncCategory> missingSynchronization) {

    public Race {
        missingSynchronization = Set.copyOf(missingSynchronization);
    }

    public String firstThread() {
        return first.threadId();
    }

    public String secondThread() {
        return second.threadId();
    }

    @Override
    public String toString() {
        return "Race on '" + location + "' between "
                + first.type() + " by " + first.threadId() + " at " + first.callSite()
                + " and "
                + second.type() + " by " + second.threadId() + " at " + second.callSite()
                + " (missing synchronization: " + missingSynchronization + ")";
    }
}
