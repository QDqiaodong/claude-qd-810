package com.drill.crew.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.drill.crew.domain.Borehole;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface BoreholeRepository extends JpaRepository<Borehole, Long> {

    List<Borehole> findByMineIdOrderByHoleNoAsc(Long mineId);

    List<Borehole> findByStatusOrderByHoleNoAsc(String status);

    boolean existsByHoleNo(String holeNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select b from Borehole b where b.id = :id")
    Optional<Borehole> findByIdForUpdate(Long id);
}
