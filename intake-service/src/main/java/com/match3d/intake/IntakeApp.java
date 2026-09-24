package com.match3d.intake;

import java.time.Clock;

import com.match3d.common.Queues;

import org.springframework.amqp.core.Queue;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts intake. Spring builds each object returned by a @Bean method once and
 * hands it to any class whose constructor asks for that type.
 */
@SpringBootApplication
@EnableScheduling
public class IntakeApp {

    public static void main(String[] args) {
        SpringApplication.run(IntakeApp.class, args);
    }

    @Bean
    IntakeStore intakeStore(EntryRepository entries, PlayerRepository players, JdbcTemplate jdbc, Clock clock) {
        return new IntakeStore(entries, players, jdbc, clock);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Durable, so a broker restart keeps what intake already published. */
    @Bean
    Queue toMatchmaking() {
        return new Queue(Queues.TO_MATCHMAKING, true);
    }

    /** Declared here too, so the listener can start before matchmaking ever runs. */
    @Bean
    Queue toIntake() {
        return new Queue(Queues.TO_INTAKE, true);
    }
}
