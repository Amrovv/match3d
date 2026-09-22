package com.match3d.intake;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who intake has queued, by entry and by player, so one player cannot
 * queue twice and a party is recorded whole or not at all.
 *
 * Every method holds the lock: checking and recording are one step.
 */
public final class QueueRegistry {

    private final Map<UUID, List<UUID>> entryToMembers = new HashMap<>();
    private final Map<UUID, UUID> memberToEntry = new HashMap<>();

    /** Records every member under entryId, or nobody if any member is already queued. */
    public synchronized boolean tryQueue(UUID entryId, List<UUID> members) {
        if (entryToMembers.containsKey(entryId)) {
            return false;
        }
        
        for (UUID member : members) {
            if (memberToEntry.containsKey(member)) {
                return false;
            }
        }

        entryToMembers.put(entryId, List.copyOf(members));
        for (UUID member : members) {
            memberToEntry.put(member, entryId);
        }
        return true;
    }

    /** Forgets the entry and all its members. False if it was not queued. */
    public synchronized boolean remove(UUID entryId) {
        List<UUID> members = entryToMembers.remove(entryId);
        if (members == null) {
            return false;
        }
        for (UUID member : members) {
            memberToEntry.remove(member);
        }
        return true;
    }

    /** The entry this player is queued in, or null. */
    public synchronized UUID entryOf(UUID playerId) {
        return memberToEntry.get(playerId);
    }
}