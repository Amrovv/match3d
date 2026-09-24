package com.match3d.matchmaking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<PlayerRow, UUID> {

    /** One statement, so two creates of the same id cannot both succeed. 1 if inserted, 0 if it existed. */
    @Transactional
    @Modifying
    @Query(value = "insert into players (id, rating) values (:id, :rating) on conflict (id) do nothing",
           nativeQuery = true)
    int insertIfAbsent(UUID id, int rating);

    /** Postgres adds, so two results touching one player cannot lose an update. Held to 1 to 5000. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update players set rating = least(5000, greatest(1, rating + :delta)) where id in (:ids)",
           nativeQuery = true)
    int adjust(Collection<UUID> ids, int delta);
}
