package com.match3d.matchmaking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.match3d.common.WaitBand;
import com.match3d.core.Lobby;
import com.match3d.core.Player;

import org.springframework.transaction.annotation.Transactional;

/** Every match formed, in Postgres. */
public class MatchHistory {

    private final MatchRepository matches;
    private final PlayerMatchRepository playerMatches;

    public MatchHistory(MatchRepository matches, PlayerMatchRepository playerMatches) {
        this.matches = matches;
        this.playerMatches = playerMatches;
    }

    /** The match and a row per player in it, all or nothing. Every player already has a players row. */
    @Transactional
    public void record(UUID matchId, Instant formedAt, Lobby lobby) {
        matches.save(new MatchRow(matchId, formedAt));

        List<PlayerMatchRow> rows = new ArrayList<>();
        for (Player p : lobby.teamA()) rows.add(new PlayerMatchRow(matchId, p.id(), 'A', p.rating(), p.queuedAt()));
        for (Player p : lobby.teamB()) rows.add(new PlayerMatchRow(matchId, p.id(), 'B', p.rating(), p.queuedAt()));
        playerMatches.saveAll(rows);
    }

    /** Removes a match whose lobby was never announced, its player rows first for the foreign key. */
    @Transactional
    public void forget(UUID matchId) {
        playerMatches.deleteByMatchId(matchId);
        matches.deleteById(matchId);
    }

    static final Duration ESTIMATE_WINDOW = Duration.ofHours(1);

    /**
     * The last hour's waits by rating band, for the heartbeat. Leavers are
     * never recorded, so these count only players who were matched.
     */
    public List<WaitBand> waitBands(Instant now) {
        List<WaitBand> bands = new ArrayList<>();
        for (Object[] row : playerMatches.waitBands(now.minus(ESTIMATE_WINDOW))) {
            bands.add(new WaitBand(((Number) row[0]).intValue(), ((Number) row[1]).doubleValue(),
                    ((Number) row[2]).longValue()));
        }
        return bands;
    }

    public Optional<MatchRecord> match(UUID matchId) {
        return matches.findById(matchId).map(row -> toRecords(List.of(row)).get(0));
    }

    /** Newest first. Empty for a player never matched. Three queries however long the history. */
    public List<MatchRecord> historyOf(UUID playerId) {
        List<UUID> ids = playerMatches.matchIdsOf(playerId);
        if (ids.isEmpty()) return List.of();
        return toRecords(matches.findByIdInOrderByFormedAtDesc(ids));
    }

    /** One query for the player rows of every match given, split into teams. Keeps the order given. */
    private List<MatchRecord> toRecords(List<MatchRow> rows) {
        Map<UUID, List<PlayerMatchRow>> byMatch = playerMatches.findByKeyMatchIdIn(rows.stream().map(MatchRow::getId).toList())
                .stream().collect(Collectors.groupingBy(PlayerMatchRow::getMatchId));

        List<MatchRecord> records = new ArrayList<>();
        for (MatchRow row : rows) {
            List<UUID> teamA = new ArrayList<>();
            List<UUID> teamB = new ArrayList<>();
            for (PlayerMatchRow played : byMatch.getOrDefault(row.getId(), List.of())) {
                (played.getSide() == 'A' ? teamA : teamB).add(played.getPlayerId());
            }
            records.add(new MatchRecord(row.getId(), row.getFormedAt(), teamA, teamB));
        }
        return records;
    }
}
