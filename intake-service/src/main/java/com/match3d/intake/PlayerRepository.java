package com.match3d.intake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlayerRepository extends JpaRepository<PlayerRow, UUID> {

    List<PlayerRow> findByEntryId(UUID entryId);

    List<PlayerRow> findByMatchId(UUID matchId);
}
