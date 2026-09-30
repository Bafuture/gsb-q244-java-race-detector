package com.example.gsb.racedetector;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A vector clock keyed by thread id. Supports merge (component-wise max) and
 * the happens-before (strictly-less-or-equal) comparison.
 */
final class VectorClock {

    private final HashMap<Long, Long> clock = new HashMap<>();

    long get(long threadId) {
        return clock.getOrDefault(threadId, 0L);
    }

    void increment(long threadId) {
        clock.merge(threadId, 1L, Long::sum);
    }

    void mergeFrom(VectorClock other) {
        other.clock.forEach((thread, value) ->
                clock.merge(thread, value, Math::max));
    }

    /** Returns true when this clock is component-wise less than or equal to {@code other}. */
    boolean happensBefore(VectorClock other) {
        for (Map.Entry<Long, Long> entry : clock.entrySet()) {
            if (entry.getValue() > other.get(entry.getKey())) {
                return false;
            }
        }
        return true;
    }

    VectorClock copy() {
        VectorClock copy = new VectorClock();
        copy.clock.putAll(clock);
        return copy;
    }

    static VectorClock fromSnapshot(Map<Long, Long> snapshot) {
        VectorClock clock = new VectorClock();
        snapshot.forEach((thread, value) -> {
            if (value > 0L) {
                clock.clock.put(thread, value);
            }
        });
        return clock;
    }

    Map<Long, Long> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(clock));
    }

    @Override
    public String toString() {
        return clock.toString();
    }
}
