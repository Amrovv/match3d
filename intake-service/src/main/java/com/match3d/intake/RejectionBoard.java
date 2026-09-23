package com.match3d.intake;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.match3d.common.EntryRejected;

/**
 * Why a player's last join was refused by matchmaking, so status can say more
 * than that they are not queued. Kept for as long as intake runs.
 *
 * Written by the listener and read by status requests on other threads.
 */
public final class RejectionBoard {

    private final Map<UUID, EntryRejected.Reason> byPlayer = new ConcurrentHashMap<>();

    /** Records the reason against every player who was in the refused entry. */
    public void record(List<UUID> players, EntryRejected.Reason reason) {
        players.forEach(player -> byPlayer.put(player, reason));
    }

    /** Why this player's last join was refused, or null if none was. */
    public EntryRejected.Reason reasonFor(UUID playerId) {
        return byPlayer.get(playerId);
    }

    /** Forgotten once they queue again, so an old refusal does not follow them. */
    public void clear(List<UUID> players) {
        players.forEach(byPlayer::remove);
    }
}
