package com.match3d.intake;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.match3d.common.EntryQueued;
import com.match3d.common.EntryRejected;
import com.match3d.common.WaitBand;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * What intake knows about every player, in its own database, so any copy of
 * intake can serve any player. One transaction per event.
 */
public class IntakeStore {

    /** How long a sent entry may go unconfirmed before the sweeper sends it again. */
    static final Duration RESEND_AFTER = Duration.ofSeconds(30);

    /** Three missed heartbeats of ten seconds. */
    static final Duration DOWN_AFTER = Duration.ofSeconds(30);

    /** Bands either side of a rating's own, so plus or minus 500. */
    static final int ESTIMATE_BANDS = 5;

    /** The entry to publish. resent is true when it was queued already and this is a retry. */
    public record Joined(UUID entryId, Instant queuedAt, boolean resent) { }

    private final EntryRepository entries;
    private final PlayerRepository players;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IntakeStore(EntryRepository entries, PlayerRepository players, JdbcTemplate jdbc, Clock clock) {
        this.entries = entries;
        this.players = players;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Queues the members as one entry, or returns their existing entry if they
     * are already queued together, exactly. Throws if any is queued elsewhere.
     */
    @Transactional
    public Joined join(UUID entryId, List<UUID> members, Instant queuedAt) {
        Set<UUID> requested = new HashSet<>(members);
        Set<UUID> current = new HashSet<>();
        for (PlayerRow row : players.findAllById(members)) {
            if (row.getEntryId() != null) current.add(row.getEntryId());
        }
        if (current.size() == 1) {
            UUID existing = current.iterator().next();
            Set<UUID> existingMembers = new HashSet<>();
            players.findByEntryId(existing).forEach(row -> existingMembers.add(row.getId()));
            if (existingMembers.equals(requested)) {
                entries.markSent(List.of(existing), clock.instant());
                return new Joined(existing, entries.findById(existing).orElseThrow().getQueuedAt(), true);
            }
        }
        if (!current.isEmpty()) throw new AlreadyQueuedException();

        if (entries.insertIfAbsent(entryId, queuedAt) == 0) throw new AlreadyQueuedException();
        // Sorted, so two joins sharing members lock them in the same order and cannot deadlock.
        for (UUID member : members.stream().sorted().toList()) {
            if (players.claim(member, entryId) == 0) throw new AlreadyQueuedException();
        }
        return new Joined(entryId, queuedAt, false);
    }

    /** What this player is doing now. No row means they have never joined. */
    public StatusResponse status(UUID playerId) {
        PlayerRow row = players.findById(playerId).orElse(null);
        if (row == null) return StatusResponse.notQueued();
        if (row.getEntryId() != null) {
            if (!matchmakingUp()) return StatusResponse.queued(row.getEntryId(), StatusResponse.Matchmaking.DOWN, null);
            Integer rating = entries.findById(row.getEntryId()).map(EntryRow::getRating).orElse(null);
            Long estimate = rating == null ? null : estimateWait(rating);
            return StatusResponse.queued(row.getEntryId(), StatusResponse.Matchmaking.UP, estimate);
        }
        if (row.getMatchId() != null) return StatusResponse.matched(matchView(row.getMatchId()));
        if (row.getRejection() != null) return StatusResponse.refused(EntryRejected.Reason.valueOf(row.getRejection()));
        return StatusResponse.notQueued();
    }

    /** Only players still on the match; one who queued again since has left it. */
    private MatchView matchView(UUID matchId) {
        List<UUID> teamA = new ArrayList<>();
        List<UUID> teamB = new ArrayList<>();
        for (PlayerRow row : players.findByMatchId(matchId)) {
            (row.getSide() == 'A' ? teamA : teamB).add(row.getId());
        }
        return new MatchView(matchId, teamA, teamB);
    }

    /**
     * Every queued entry as its join, with its original queue time, marked
     * sent and unconfirmed, for a restarted engine. Three statements however
     * many entries.
     */
    @Transactional
    public List<EntryQueued> requeueAll() {
        Map<UUID, List<UUID>> members = new HashMap<>();
        for (PlayerRow row : players.findByEntryIdIsNotNull()) {
            members.computeIfAbsent(row.getEntryId(), id -> new ArrayList<>()).add(row.getId());
        }
        List<EntryQueued> joins = new ArrayList<>();
        for (EntryRow entry : entries.findAll()) {
            joins.add(new EntryQueued(entry.getId(), members.get(entry.getId()), entry.getQueuedAt()));
        }
        if (!joins.isEmpty()) entries.markSent(joins.stream().map(EntryQueued::entryId).toList(), clock.instant());
        return joins;
    }

    /**
     * Entries sent over 30 seconds ago and never confirmed, as joins with their
     * original queue times, marked sent again. Such an entry was lost between
     * an intake commit and its publish, or matchmaking is down.
     */
    @Transactional
    public List<EntryQueued> resendDue() {
        Instant now = clock.instant();
        List<EntryRow> due = entries.findByAcceptedAtIsNullAndSentAtBefore(now.minus(RESEND_AFTER));
        if (due.isEmpty()) return List.of();

        List<UUID> ids = due.stream().map(EntryRow::getId).toList();
        Map<UUID, List<UUID>> members = new HashMap<>();
        for (PlayerRow row : players.findByEntryIdIn(ids)) {
            members.computeIfAbsent(row.getEntryId(), id -> new ArrayList<>()).add(row.getId());
        }
        List<EntryQueued> joins = new ArrayList<>();
        for (EntryRow entry : due) {
            joins.add(new EntryQueued(entry.getId(), members.get(entry.getId()), entry.getQueuedAt()));
        }
        entries.markSent(ids, now);
        return joins;
    }

    /** Matchmaking confirmed the entry, at this rating. Ignored for one intake no longer holds. */
    @Transactional
    public void accepted(UUID entryId, int rating) {
        entries.accept(entryId, rating, clock.instant());
    }

    /** Stamped with intake's clock, not the sender's, so up or down never depends on the two agreeing. */
    @Transactional
    public void heartbeat(List<WaitBand> bands) {
        jdbc.update("""
                insert into heartbeat (id, last_seen) values (1, ?)
                on conflict (id) do update set last_seen = excluded.last_seen""", Timestamp.from(clock.instant()));
        jdbc.update("delete from wait_bands");
        jdbc.batchUpdate("insert into wait_bands (band, total_wait_seconds, players) values (?, ?, ?)", bands,
                bands.size(), (ps, band) -> {
                    ps.setInt(1, band.band());
                    ps.setDouble(2, band.totalWaitSeconds());
                    ps.setLong(3, band.players());
                });
    }

    /** Up if a heartbeat arrived within the last 30 seconds. Down if none ever has. */
    public boolean matchmakingUp() {
        List<Timestamp> seen = jdbc.queryForList("select last_seen from heartbeat where id = 1", Timestamp.class);
        return !seen.isEmpty() && seen.get(0).toInstant().isAfter(clock.instant().minus(DOWN_AFTER));
    }

    /** Mean wait over the bands within 500 of this rating, whole seconds. Null with nothing to average. */
    private Long estimateWait(int rating) {
        int band = WaitBand.of(rating);
        return jdbc.queryForObject("""
                select case when sum(players) > 0 then round(sum(total_wait_seconds) / sum(players)) end
                from wait_bands where band between ? and ?""", Long.class,
                band - ESTIMATE_BANDS, band + ESTIMATE_BANDS);
    }

    public boolean isQueued(UUID entryId) {
        return entries.existsById(entryId);
    }

    /** Forgets a queued entry, on leave or when its join could not be published. */
    @Transactional
    public void forget(UUID entryId) {
        players.release(entryId);
        entries.deleteById(entryId);
    }

    /** Both teams as entry ids. Entries no longer held, such as one that left, are skipped. */
    @Transactional
    public void matched(UUID matchId, List<UUID> teamA, List<UUID> teamB) {
        for (UUID entryId : teamA) players.match(entryId, matchId, "A");
        for (UUID entryId : teamB) players.match(entryId, matchId, "B");
        entries.deleteAllById(concat(teamA, teamB));
    }

    @Transactional
    public void refused(UUID entryId, EntryRejected.Reason reason) {
        players.refuse(entryId, reason.name());
        entries.deleteById(entryId);
    }

    @Transactional
    public void ended(UUID matchId) {
        players.endMatch(matchId);
    }

    private static List<UUID> concat(List<UUID> a, List<UUID> b) {
        List<UUID> both = new ArrayList<>(a);
        both.addAll(b);
        return both;
    }
}
