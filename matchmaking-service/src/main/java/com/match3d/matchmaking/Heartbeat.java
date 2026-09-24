package com.match3d.matchmaking;

import java.time.Clock;

import com.match3d.common.MatchmakingAlive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Tells intake every ten seconds that matchmaking is up, with the last hour's waits by rating band. */
@Component
public class Heartbeat {

    static final long INTERVAL_MILLIS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(Heartbeat.class);

    private final MatchHistory history;
    private final EventPublisher publisher;
    private final Clock clock;

    public Heartbeat(MatchHistory history, EventPublisher publisher, Clock clock) {
        this.history = history;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** A failure is logged, not thrown, so the schedule keeps running; intake reads silence as down. */
    @Scheduled(fixedRate = INTERVAL_MILLIS)
    public void beat() {
        try {
            publisher.publish(new MatchmakingAlive(clock.instant(), history.waitBands(clock.instant())));
        } catch (RuntimeException e) {
            log.warn("Heartbeat not sent", e);
        }
    }
}
