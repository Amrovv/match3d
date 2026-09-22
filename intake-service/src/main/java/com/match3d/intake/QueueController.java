package com.match3d.intake;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.match3d.common.EntryLeft;
import com.match3d.common.EntryQueued;
import com.match3d.common.EntryRejected;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The queue endpoints: join, leave, and a player's status.
 *
 * Join records before it publishes, so a double click is refused, and undoes
 * the record if the broker is down. Leave publishes before it forgets, so
 * intake never drops an entry matchmaking still holds.
 */
@RestController
public class QueueController {

    /**
     * A party fills at most one team of five. The rule belongs to the engine,
     * which intake cannot see, so it is repeated rather than shared.
     */
    private static final int MAX_MEMBERS = 5;

    private final QueueRegistry registry;
    private final EventPublisher publisher;
    private final Clock clock;
    private final RejectionBoard rejections;
    private final MatchBoard board;

    public QueueController(QueueRegistry registry, EventPublisher publisher, Clock clock,
                           RejectionBoard rejections, MatchBoard board) {
        this.registry = registry;
        this.publisher = publisher;
        this.clock = clock;
        this.rejections = rejections;
        this.board = board;
    }

    /**
     * 202 with the entry id, 400 for anything but 1 to 5 distinct ids, 409 if
     * anyone listed is already queued, 503 if the broker is down.
     */
    @PostMapping("/queue/join")
    public ResponseEntity<?> join(@RequestBody JoinRequest request) {
        List<UUID> members = request.memberIds();
        if (members == null || members.isEmpty() || members.size() > MAX_MEMBERS) {
            return ResponseEntity.badRequest().body("a join needs to have 1 to 5 members");
        }
        if (new HashSet<>(members).size() != members.size()) {
            return ResponseEntity.badRequest().body("a join needs to have unique members");
        }

        UUID entryId = members.get(0);
        if (members.size() > 1) {
            entryId = UUID.randomUUID();
        }

        if (!registry.tryQueue(entryId, members)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("a member of the party is already queued");
        }
        rejections.clear(members);

        try {
            publisher.publish(new EntryQueued(entryId, members, Instant.now(clock)));
        } catch (UncheckedIOException e) {
            registry.remove(entryId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("service unavailable");
        }
        return ResponseEntity.accepted().body(new JoinResponse(entryId));
    }

    /**
     * 202 once the leave is published, 400 without an entry id, 404 if
     * nothing is queued under it, 503 if the broker is down, in which case the
     * entry stays queued.
     */
    @PostMapping("/queue/leave")
    public ResponseEntity<?> leave(@RequestBody LeaveRequest request) {
        UUID entryId = request.entryId();
        if (entryId == null) {
            return ResponseEntity.badRequest().body("an entry id is required");
        }

        if (!registry.contains(entryId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("no entry queued under that id");
        }

        try {
            publisher.publish(new EntryLeft(entryId));
        } catch (UncheckedIOException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("service unavailable");
        }

        registry.remove(entryId);
        return ResponseEntity.accepted().build();
    }

    /** What this player is doing now: queued, matched, refused, or none of those. */
    @GetMapping("/queue/status/{playerId}")
    public StatusResponse status(@PathVariable UUID playerId) {
        UUID entryId = registry.entryOf(playerId);
        if (entryId != null) return StatusResponse.queued(entryId);

        MatchView match = board.matchOf(playerId);
        if (match != null) return StatusResponse.matched(match);

        EntryRejected.Reason reason = rejections.reasonFor(playerId);
        if (reason != null) return StatusResponse.refused(reason);

        return StatusResponse.notQueued();
    }
}
