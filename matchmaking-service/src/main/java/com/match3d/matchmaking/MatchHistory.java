package com.match3d.matchmaking;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.match3d.core.Lobby;
import com.match3d.core.Player;

import org.springframework.transaction.annotation.Transactional;

/** Every match formed, in Postgres. */
public class MatchHistory {

    private final MatchRepository matches;
    private final PlayerMatchRepository seats;

    public MatchHistory(MatchRepository matches, PlayerMatchRepository seats) {
        this.matches = matches;
        this.seats = seats;
    }

    /** The match and a seat per player, all or nothing. Every player already has a row. */
    @Transactional
    public void record(UUID matchId, Instant formedAt, Lobby lobby) {
        matches.save(new MatchRow(matchId, formedAt));

        List<PlayerMatchRow> rows = new ArrayList<>();
        for (Player p : lobby.teamA()) rows.add(new PlayerMatchRow(matchId, p.id(), 'A', p.rating(), p.queuedAt()));
        for (Player p : lobby.teamB()) rows.add(new PlayerMatchRow(matchId, p.id(), 'B', p.rating(), p.queuedAt()));
        seats.saveAll(rows);
    }

    public Optional<MatchRecord> match(UUID matchId) {
        return matches.findById(matchId).map(row -> toRecords(List.of(row)).get(0));
    }

    /** Newest first. Empty for a player never matched. Three queries however long the history. */
    public List<MatchRecord> historyOf(UUID playerId) {
        List<UUID> ids = seats.matchIdsOf(playerId);
        if (ids.isEmpty()) return List.of();
        return toRecords(matches.findByIdInOrderByFormedAtDesc(ids));
    }

    /** One query for the seats of every match given, split into teams. Keeps the order given. */
    private List<MatchRecord> toRecords(List<MatchRow> rows) {
        Map<UUID, List<PlayerMatchRow>> seatsByMatch = seats.findByKeyMatchIdIn(rows.stream().map(MatchRow::getId).toList())
                .stream().collect(Collectors.groupingBy(PlayerMatchRow::getMatchId));

        List<MatchRecord> records = new ArrayList<>();
        for (MatchRow row : rows) {
            List<UUID> teamA = new ArrayList<>();
            List<UUID> teamB = new ArrayList<>();
            for (PlayerMatchRow seat : seatsByMatch.getOrDefault(row.getId(), List.of())) {
                (seat.getSide() == 'A' ? teamA : teamB).add(seat.getPlayerId());
            }
            records.add(new MatchRecord(row.getId(), row.getFormedAt(), teamA, teamB));
        }
        return records;
    }
}
