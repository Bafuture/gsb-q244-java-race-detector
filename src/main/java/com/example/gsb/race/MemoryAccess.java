package com.example.gsb.race;

import java.util.Set;

/**
 * A single registered read or write of a memory location.
 *
 * @param sequence   monotonically increasing access sequence number
 * @param location   identifier of the accessed memory location
 * @param threadId   thread performing the access
 * @param type       read or write
 * @param callSite   human-readable caller location (class.method(file:line))
 * @param heldLocks  monitors held by the thread when the access occurred
 */
public record MemoryAccess(
        long sequence,
        String location,
        String threadId,
        AccessType type,
        String callSite,
        Set<String> heldLocks) {

    public MemoryAccess {
        heldLocks = Set.copyOf(heldLocks);
    }
}
