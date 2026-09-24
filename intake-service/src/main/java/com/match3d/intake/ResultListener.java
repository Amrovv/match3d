package com.match3d.intake;

import com.match3d.common.EntryMatched;
import com.match3d.common.EntryRejected;
import com.match3d.common.EventJson;
import com.match3d.common.MatchEnded;
import com.match3d.common.Queues;

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

    private final IntakeStore store;

    public ResultListener(IntakeStore store) {
        this.store = store;
    }

    @RabbitListener(queues = Queues.TO_INTAKE)
    public void onMessage(Message message) {
        String type = message.getMessageProperties().getType();
        byte[] body = message.getBody();
        switch (type) {
            case "EntryMatched" -> onMatched(EventJson.fromBytes(body, EntryMatched.class));
            case "EntryRejected" -> onRejected(EventJson.fromBytes(body, EntryRejected.class));
            case "MatchEnded" -> store.ended(EventJson.fromBytes(body, MatchEnded.class).matchId());
            default -> throw new IllegalArgumentException("Unknown event type " + type);
        }
    }

    void onMatched(EntryMatched matched) {
        store.matched(matched.matchId(), matched.teamA(), matched.teamB());
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
}
