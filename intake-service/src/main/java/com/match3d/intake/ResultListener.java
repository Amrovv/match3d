package com.match3d.intake;

import java.io.UncheckedIOException;
import java.util.List;

import com.match3d.common.EntryAccepted;
import com.match3d.common.EntryMatched;
import com.match3d.common.EntryQueued;
import com.match3d.common.EntryRejected;
import com.match3d.common.EventJson;
import com.match3d.common.MatchEnded;
import com.match3d.common.MatchmakingAlive;
import com.match3d.common.MatchmakingStarted;
import com.match3d.common.Queues;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes what matchmaking sends back. Each message names its event in the
 * type property, which decides the record its bytes are read as.
 *
 * Runs on a listener thread, alongside request threads; the database keeps them apart.
 */
@Component
public class ResultListener {

    private static final Logger log = LoggerFactory.getLogger(ResultListener.class);

    private final IntakeStore store;
    private final EventPublisher publisher;

    public ResultListener(IntakeStore store, EventPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    @RabbitListener(queues = Queues.TO_INTAKE)
    public void onMessage(Message message) {
        String type = message.getMessageProperties().getType();
        byte[] body = message.getBody();
        switch (type) {
            case "EntryMatched" -> onMatched(EventJson.fromBytes(body, EntryMatched.class));
            case "EntryAccepted" -> onAccepted(EventJson.fromBytes(body, EntryAccepted.class));
            case "EntryRejected" -> onRejected(EventJson.fromBytes(body, EntryRejected.class));
            case "MatchEnded" -> store.ended(EventJson.fromBytes(body, MatchEnded.class).matchId());
            case "MatchmakingStarted" -> onStarted(EventJson.fromBytes(body, MatchmakingStarted.class));
            case "MatchmakingAlive" -> store.heartbeat(EventJson.fromBytes(body, MatchmakingAlive.class).bands());
            default -> throw new IllegalArgumentException("Unknown event type " + type);
        }
    }

    void onMatched(EntryMatched matched) {
        store.matched(matched.matchId(), matched.teamA(), matched.teamB());
    }

    void onAccepted(EntryAccepted accepted) {
        store.accepted(accepted.entryId(), accepted.rating());
    }

    /** Keeps the reason against the members and frees them, unless the entry is queued already. */
    void onRejected(EntryRejected rejected) {
        // An expression, so a new reason fails to compile until it is handled here.
        boolean refused = switch (rejected.reason()) {
            case SPREAD_TOO_WIDE, UNKNOWN_PLAYER -> true;
            case DUPLICATE -> false; // redelivery of an entry already queued
        };
        if (refused) store.refused(rejected.entryId(), rejected.reason());
    }

    /**
     * Matchmaking's engine is empty, so every entry still queued here is sent
     * again, keeping its place. Entries it already held are refused as
     * duplicates, which changes nothing.
     */
    void onStarted(MatchmakingStarted started) {
        List<EntryQueued> joins = store.requeueAll();
        log.info("Matchmaking started at {}, requeuing {} entries", started.startedAt(), joins.size());
        int failed = 0;
        for (EntryQueued join : joins) {
            try {
                publisher.publish(join);
            } catch (UncheckedIOException e) {
                failed++;
            }
        }
        if (failed > 0) log.error("{} of {} entries could not be requeued and are stranded", failed, joins.size());
    }
}
