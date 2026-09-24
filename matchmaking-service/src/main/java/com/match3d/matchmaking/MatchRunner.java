package com.match3d.matchmaking;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.match3d.common.EntryMatched;
import com.match3d.core.Lobby;
import com.match3d.core.MatchMaker;
import com.match3d.core.Party;
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

    /**
     * Passes until one comes back empty. Skipped below ten players, when none
     * can succeed. Stops on a failed announce, or it would reform the same
     * lobby at once while the broker is down.
     */
    void runPasses() {
        while (index.playerCount() >= LOBBY_PLAYERS) {
            Optional<Lobby> lobby = matcher.formLobby(clock.instant());
            if (lobby.isEmpty()) return;
            if (!announce(lobby.get())) return;
        }
    }

    /**
     * Records the match and tells intake. If the publish fails, the match is
     * removed and its entries go back to the engine with their original queue
     * times, as if it never formed. If the process dies first, intake requeues
     * them on restart and the recorded match is never resulted.
     */
    boolean announce(Lobby lobby) {
        UUID matchId = UUID.randomUUID();
        history.record(matchId, clock.instant(), lobby);

        List<UUID> teamA = entries(lobby.teamA());
        List<UUID> teamB = entries(lobby.teamB());
        try {
            publisher.publish(new EntryMatched(matchId, teamA, teamB));
        } catch (RuntimeException e) {
            log.warn("Lobby {} not announced, returning its entries to the engine", matchId, e);
            history.forget(matchId);
            requeue(lobby);
            return false;
        }
        teamA.forEach(book::forget);
        teamB.forEach(book::forget);
        return true;
    }

    /** Rebuilds each entry from its members: a solo as itself, a party whole under its entry id. */
    private void requeue(Lobby lobby) {
        Map<UUID, List<Player>> byEntry = new LinkedHashMap<>();
        for (Player p : lobby.members()) {
            byEntry.computeIfAbsent(book.entryOf(p.id()), id -> new ArrayList<>()).add(p);
        }
        byEntry.forEach((entryId, members) -> matcher.enqueue(members.size() == 1
                ? members.get(0)
                : Party.of(entryId, members, members.get(0).queuedAt())));
    }

    /** Members of a party share one entry, so each entry appears once. */
    private List<UUID> entries(List<Player> players) {
        return players.stream().map(p -> book.entryOf(p.id())).distinct().toList();
    }
}
