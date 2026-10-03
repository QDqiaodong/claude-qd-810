package com.drill.crew.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.drill.crew.domain.HandoverVersion;

public interface HandoverVersionRepository extends JpaRepository<HandoverVersion, Long> {

    @Query("select v from HandoverVersion v where v.boreholeId = :boreholeId and v.active = true")
    Optional<HandoverVersion> findActiveByBoreholeId(Long boreholeId);

    List<HandoverVersion> findByBoreholeIdOrderByVersionNoDescIdDesc(Long boreholeId);

    @Query("select coalesce(max(v.versionNo), 0) from HandoverVersion v where v.boreholeId = :boreholeId")
    Integer maxVersionNo(Long boreholeId);
}
