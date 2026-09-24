package com.match3d.intake;

import java.io.UncheckedIOException;
import java.util.List;

import com.match3d.common.EntryQueued;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every ten seconds, sends again any entry matchmaking has not confirmed within
 * 30 seconds of it being sent. Every intake copy sweeps; an entry sent twice is
 * refused by the engine as a duplicate, which changes nothing.
 */
@Component
public class Sweeper {

    static final long INTERVAL_MILLIS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);

    private final IntakeStore store;
    private final EventPublisher publisher;

    public Sweeper(IntakeStore store, EventPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    /** A failed publish is left marked sent, so the next sweep after 30 seconds tries again. */
    @Scheduled(fixedDelay = INTERVAL_MILLIS)
    public void sweep() {
        List<EntryQueued> due = store.resendDue();
        if (due.isEmpty()) return;
        int failed = 0;
        for (EntryQueued join : due) {
            try {
                publisher.publish(join);
            } catch (UncheckedIOException e) {
                failed++;
            }
        }
        log.warn("Sent {} unconfirmed entries again, {} failed", due.size(), failed);
    }
}
