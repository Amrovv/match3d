package com.match3d.matchmaking;

import java.time.Clock;

import com.match3d.common.MatchmakingStarted;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Tells intake this process started with an empty engine, once the broker connection is up. */
@Component
public class StartupAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(StartupAnnouncer.class);

    private final EventPublisher publisher;
    private final Clock clock;

    public StartupAnnouncer(EventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    /** A failure is logged, not thrown: matchmaking still runs, but intake keeps entries the engine lost. */
    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        try {
            publisher.publish(new MatchmakingStarted(clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not announce the restart; entries queued before it are stranded", e);
        }
    }
}
