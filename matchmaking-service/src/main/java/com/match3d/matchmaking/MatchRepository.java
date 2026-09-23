package com.match3d.matchmaking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MatchRepository extends JpaRepository<MatchRow, UUID> {

    List<MatchRow> findByIdInOrderByFormedAtDesc(Collection<UUID> ids);
}
