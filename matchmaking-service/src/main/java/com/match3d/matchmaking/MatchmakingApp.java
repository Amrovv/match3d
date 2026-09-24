package com.match3d.matchmaking;

import java.time.Clock;

import com.match3d.common.Queues;
import com.match3d.core.FairnessHeap;
import com.match3d.core.MatchMaker;
import com.match3d.core.SkillIndex;

import org.springframework.amqp.core.Queue;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Starts matchmaking. One engine per process, shared by the consumer and the runner. */
@SpringBootApplication
public class MatchmakingApp {

    public static void main(String[] args) {
        SpringApplication.run(MatchmakingApp.class, args);
    }

    @Bean
    SkillIndex skillIndex() {
        return new SkillIndex();
    }

    @Bean
    FairnessHeap fairnessHeap() {
        return new FairnessHeap();
    }

    @Bean
    MatchMaker matchMaker(SkillIndex index, FairnessHeap heap) {
        return new MatchMaker(index, heap);
    }

    @Bean
    RatingStore ratingStore(PlayerRepository players) {
        return new RatingStore(players);
    }

    @Bean
    EntryBook entryBook() {
        return new EntryBook();
    }

    @Bean
    MatchHistory matchHistory(MatchRepository matches, PlayerMatchRepository seats) {
        return new MatchHistory(matches, seats);
    }

    @Bean
    MatchResults matchResults(MatchRepository matches, PlayerMatchRepository seats,
                              PlayerRepository players, Clock clock) {
        return new MatchResults(matches, seats, players, clock);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Declared on both sides, so either service can start first. */
    @Bean
    Queue toMatchmaking() {
        return new Queue(Queues.TO_MATCHMAKING, true);
    }

    @Bean
    Queue toIntake() {
        return new Queue(Queues.TO_INTAKE, true);
    }
}
