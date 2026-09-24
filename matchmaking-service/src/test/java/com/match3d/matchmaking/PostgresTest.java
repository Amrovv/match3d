package com.match3d.matchmaking;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Repositories over a throwaway Postgres, with the real migrations and no
 * broker. Each test runs in a transaction rolled back at its end.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class PostgresTest {

    /** One container for every test class, removed when the JVM exits. */
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    static {
        POSTGRES.start();
    }

    @Autowired private JdbcTemplate jdbc;

    /** Empties every table. Tests outside the test transaction commit, so they call this when done. */
    void wipe() {
        jdbc.execute("truncate player_matches, matches, players");
    }

    @Autowired private PlayerRepository playerRows;

    /** Writes a rating by hand, creating the row if missing. Only tests do this. */
    void rate(UUID playerId, int rating) {
        playerRows.save(new PlayerRow(playerId, rating));
    }
}
