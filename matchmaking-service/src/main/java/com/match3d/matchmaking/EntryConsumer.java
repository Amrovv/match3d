package com.match3d.matchmaking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.match3d.common.EntryLeft;
import com.match3d.common.EntryQueued;
import com.match3d.common.EntryRejected;
import com.match3d.common.EventJson;
import com.match3d.common.Queues;
import com.match3d.core.MatchMaker;
import com.match3d.core.Party;
import com.match3d.core.Player;
import com.match3d.core.QueueEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes joins and leaves from intake into the engine. One listener thread,
 * so events for an entry are handled in the order intake sent them.
 */
@Component
public class EntryConsumer {

    private static final Logger log = LoggerFactory.getLogger(EntryConsumer.class);

    private final MatchMaker matcher;
    private final RatingStore ratings;
    private final EntryBook book;
    private final EventPublisher publisher;
    private final MatchRunner runner;

    public EntryConsumer(MatchMaker matcher, RatingStore ratings, EntryBook book,
                         EventPublisher publisher, MatchRunner runner) {
        this.matcher = matcher;
        this.ratings = ratings;
        this.book = book;
        this.publisher = publisher;
        this.runner = runner;
    }

    @RabbitListener(queues = Queues.TO_MATCHMAKING)
    public void onMessage(Message message) {
        String type = message.getMessageProperties().getType();
        byte[] body = message.getBody();
        switch (type) {
            case "EntryQueued" -> onQueued(EventJson.fromBytes(body, EntryQueued.class));
            case "EntryLeft" -> onLeft(EventJson.fromBytes(body, EntryLeft.class));
            default -> throw new IllegalArgumentException("Unknown event type " + type);
        }
    }

    /** Recorded before enqueue, so a lobby formed at once can be translated. */
    void onQueued(EntryQueued queued) {
        Optional<QueueEntry> entry;
        try {
            entry = toEntry(queued);
        } catch (IllegalArgumentException e) {
            // Intake checks sizes, so the one refusal left is the spread cap.
            publisher.publish(new EntryRejected(queued.entryId(), EntryRejected.Reason.SPREAD_TOO_WIDE));
            return;
        }
        if (entry.isEmpty()) {
            log.warn("Refused entry {}: unknown player among {}", queued.entryId(), queued.memberIds());
            publisher.publish(new EntryRejected(queued.entryId(), EntryRejected.Reason.UNKNOWN_PLAYER));
            return;
        }

        book.record(queued.entryId(), queued.memberIds());
        if (matcher.enqueue(entry.get())) {
            runner.wake();
        } else {
            publisher.publish(new EntryRejected(queued.entryId(), EntryRejected.Reason.DUPLICATE));
        }
    }

    /** False from withdraw means already matched, and the runner forgets that entry itself. */
    void onLeft(EntryLeft left) {
        if (matcher.withdraw(left.entryId())) book.forget(left.entryId());
    }

    /** Empty if any member has no players row. */
    private Optional<QueueEntry> toEntry(EntryQueued queued) {
        if (queued.memberIds().size() == 1) {
            UUID id = queued.entryId();
            return ratings.ratingOf(id).<QueueEntry>map(rating -> new Player(id, rating, queued.queuedAt()));
        }
        Map<UUID, Integer> rated = ratings.ratingOf(queued.memberIds());
        if (rated.size() < queued.memberIds().size()) return Optional.empty();
        List<Player> members = new ArrayList<>();
        for (UUID id : queued.memberIds()) {
            members.add(new Player(id, rated.get(id), queued.queuedAt()));
        }
        return Optional.of(Party.of(queued.entryId(), members, queued.queuedAt()));
    }
}
