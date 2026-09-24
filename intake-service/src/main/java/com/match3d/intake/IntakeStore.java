package com.match3d.intake;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.match3d.common.EntryRejected;

import org.springframework.transaction.annotation.Transactional;

/**
 * What intake knows about every player, in its own database, so any copy of
 * intake can serve any player. One transaction per event.
 */
public class IntakeStore {

    /** The entry to publish. resent is true when it was queued already and this is a retry. */
    public record Joined(UUID entryId, Instant queuedAt, boolean resent) { }

    private final EntryRepository entries;
    private final PlayerRepository players;

    public IntakeStore(EntryRepository entries, PlayerRepository players) {
        this.entries = entries;
        this.players = players;
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
        if (row.getEntryId() != null) return StatusResponse.queued(row.getEntryId());
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
