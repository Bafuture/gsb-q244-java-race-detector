package com.example.gsb.race;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable vector clock used to capture the happens-before relation.
 * Not thread-safe; guarded by the owning detector's monitor.
 */
final class VectorClock {

    private final Map<String, Long> entries = new HashMap<>();

    long get(String threadId) {
        return entries.getOrDefault(threadId, 0L);
    }

    void increment(String threadId) {
        entries.merge(threadId, 1L, Long::sum);
    }

    /**
     * Returns true when every component of this clock is less than or equal
     * to the corresponding component of {@code other}, i.e. the event
     * stamped with this clock happens-before the event stamped with
     * {@code other}.
     */
    boolean isLessOrEqual(VectorClock other) {
        for (Map.Entry<String, Long> entry : entries.entrySet()) {
            if (entry.getValue() > other.get(entry.getKey())) {
                return false;
            }
        }
        return true;
    }

    void merge(VectorClock other) {
        other.entries.forEach((thread, value) -> entries.merge(thread, value, Math::max));
    }

    VectorClock copy() {
        VectorClock copy = new VectorClock();
        copy.entries.putAll(entries);
        return copy;
    }
}
