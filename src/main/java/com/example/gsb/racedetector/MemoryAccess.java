package com.example.gsb.racedetector;

import java.util.Map;

/**
 * A single registered read or write of a memory location.
 *
 * @param sequence    detector-wide access sequence number
 * @param threadId    thread that performed the access
 * @param threadName  name of the accessing thread (for reporting)
 * @param location    identifier of the accessed memory location
 * @param type        read or write
 * @param callSite    caller code location, e.g. {@code com.foo.Bar.run(Bar.java:42)}
 * @param clockSnapshot vector clock of the accessing thread at the time of access
 */
public record MemoryAccess(
        long sequence,
        long threadId,
        String threadName,
        String location,
        AccessType type,
        String callSite,
        Map<Long, Long> clockSnapshot
) {
    public MemoryAccess {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("location must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        clockSnapshot = Map.copyOf(clockSnapshot);
    }

    public boolean isWrite() {
        return type == AccessType.WRITE;
    }
}
