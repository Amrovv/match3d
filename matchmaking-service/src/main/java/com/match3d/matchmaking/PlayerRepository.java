package com.match3d.matchmaking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PlayerRepository extends JpaRepository<PlayerRow, UUID> {
}
