package com.uathub.repo;

import com.uathub.domain.CycleSignOff;
import com.uathub.domain.UatCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CycleSignOffRepository extends JpaRepository<CycleSignOff, Long> {
    List<CycleSignOff> findByUatCycle(UatCycle cycle);
    Optional<CycleSignOff> findByUatCycleAndLob(UatCycle cycle, String lob);
}
