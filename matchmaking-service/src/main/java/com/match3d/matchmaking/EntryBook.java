package com.match3d.matchmaking;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which entry each queued player belongs to. A lobby holds players, but intake
 * names entries, so a formed lobby is translated back through this.
 *
 * Written by the consumer thread, read by the runner.
 */
public class EntryBook {

    private final Map<UUID, List<UUID>> membersOf = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> entryOf = new ConcurrentHashMap<>();

    public void record(UUID entryId, List<UUID> memberIds) {
        membersOf.put(entryId, List.copyOf(memberIds));
        for (UUID member : memberIds) entryOf.put(member, entryId);
    }

    public void forget(UUID entryId) {
        List<UUID> members = membersOf.remove(entryId);
        if (members != null) members.forEach(entryOf::remove);
    }

    /** Null if that player is in no entry held here. */
    public UUID entryOf(UUID playerId) {
        return entryOf.get(playerId);
    }
}
