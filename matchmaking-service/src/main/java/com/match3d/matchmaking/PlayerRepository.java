package com.match3d.matchmaking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface PlayerRepository extends JpaRepository<PlayerRow, UUID> {

    /** One statement, so two creates of the same id cannot both succeed. 1 if inserted, 0 if it existed. */
    @Transactional
    @Modifying
    @Query(value = "insert into players (id, rating) values (:id, :rating) on conflict (id) do nothing",
           nativeQuery = true)
    int insertIfAbsent(UUID id, int rating);
}
