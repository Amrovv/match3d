package com.match3d.matchmaking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Every match formed, by id and by player. In memory until persistence, so it only grows. */
public class MatchHistory {

    private final Map<UUID, MatchRecord> byId = new ConcurrentHashMap<>();
    private final Map<UUID, List<MatchRecord>> byPlayer = new ConcurrentHashMap<>();

    public void record(MatchRecord match) {
        byId.put(match.matchId(), match);
        for (UUID player : match.teamA()) append(player, match);
        for (UUID player : match.teamB()) append(player, match);
    }

    private void append(UUID player, MatchRecord match) {
        byPlayer.compute(player, (id, list) -> {
            List<MatchRecord> next = list == null ? new ArrayList<>() : new ArrayList<>(list);
            next.add(0, match);
            return List.copyOf(next);
        });
    }

    public Optional<MatchRecord> match(UUID matchId) {
        return Optional.ofNullable(byId.get(matchId));
    }

    /** Newest first. Empty for a player never matched. */
    public List<MatchRecord> historyOf(UUID playerId) {
        return byPlayer.getOrDefault(playerId, List.of());
    }
}
