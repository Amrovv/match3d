package com.match3d.matchmaking;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.match3d.common.EntryMatched;
import com.match3d.core.Lobby;
import com.match3d.core.MatchMaker;
import com.match3d.core.Player;
import com.match3d.core.SkillIndex;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one thread that runs passes. Runs them until one comes back empty, then
 * waits for a join or a second, since windows widen with time alone.
 */
@Component
public class MatchRunner {

    private static final Logger log = LoggerFactory.getLogger(MatchRunner.class);

    static final int LOBBY_PLAYERS = 10;
    static final long WAIT_MILLIS = 1000;

    private final MatchMaker matcher;
    private final SkillIndex index;
    private final EntryBook book;
    private final MatchHistory history;
    private final EventPublisher publisher;
    private final Clock clock;

    private final Semaphore wake = new Semaphore(0);
    private volatile boolean running;
    private Thread thread;

    public MatchRunner(MatchMaker matcher, SkillIndex index, EntryBook book,
                       MatchHistory history, EventPublisher publisher, Clock clock) {
        this.matcher = matcher;
        this.index = index;
        this.book = book;
        this.history = history;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Called on every join. Surplus permits are drained before the next round. */
    public void wake() {
        wake.release();
    }

    @PostConstruct
    void start() {
        running = true;
        thread = new Thread(this::loop, "match-runner");
        thread.start();
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        thread.interrupt();
        thread.join();
    }

    private void loop() {
        while (running) {
            // Drained before the passes, so a join during them still wakes the next round.
            wake.drainPermits();
            try {
                runPasses();
            } catch (RuntimeException e) {
                log.error("Pass failed", e);
            }
            try {
                wake.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Passes until one comes back empty. Skipped below ten players, when none can succeed. */
    void runPasses() {
        while (index.playerCount() >= LOBBY_PLAYERS) {
            Optional<Lobby> lobby = matcher.formLobby(clock.instant());
            if (lobby.isEmpty()) return;
            announce(lobby.get());
        }
    }

    /**
     * Records the match and tells intake. If the publish fails or the process
     * dies first, the lobby is lost while intake still holds its entries.
     */
    void announce(Lobby lobby) {
        UUID matchId = UUID.randomUUID();
        history.record(matchId, clock.instant(), lobby);

        List<UUID> teamA = entries(lobby.teamA());
        List<UUID> teamB = entries(lobby.teamB());
        publisher.publish(new EntryMatched(matchId, teamA, teamB));
        teamA.forEach(book::forget);
        teamB.forEach(book::forget);
    }

    /** Members of a party share one entry, so each entry appears once. */
    private List<UUID> entries(List<Player> players) {
        return players.stream().map(p -> book.entryOf(p.id())).distinct().toList();
    }
}
