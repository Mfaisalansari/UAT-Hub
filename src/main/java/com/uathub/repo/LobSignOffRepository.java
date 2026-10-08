package com.uathub.repo;

import com.uathub.domain.LobSignOff;
import com.uathub.domain.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LobSignOffRepository extends JpaRepository<LobSignOff, Long> {
    List<LobSignOff> findByRun(TestRun run);
    Optional<LobSignOff> findByRunAndLob(TestRun run, String lob);
}
