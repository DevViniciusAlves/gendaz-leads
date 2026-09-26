package com.gendaz.leads.repository;

import com.gendaz.leads.entity.OsmSyncRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OsmSyncRunRepository extends JpaRepository<OsmSyncRun, Long> {

    Optional<OsmSyncRun> findFirstByRegionIdAndStatusInOrderByCreatedAtDesc(
            Long regionId, List<String> statuses);

    List<OsmSyncRun> findByRegionIdOrderByCreatedAtDesc(Long regionId);

    Optional<OsmSyncRun> findByIdAndStatusIn(Long id, List<String> statuses);

    @Query("SELECT s FROM OsmSyncRun s WHERE s.region.id = :regionId AND s.status IN :statuses")
    Optional<OsmSyncRun> findActiveByRegionId(@Param("regionId") Long regionId, @Param("statuses") List<String> statuses);

    Optional<OsmSyncRun> findByRequestKey(String requestKey);

    @Query("SELECT s FROM OsmSyncRun s WHERE s.target.id = :targetId AND s.status IN :statuses")
    Optional<OsmSyncRun> findActiveByTargetId(@Param("targetId") Long targetId, @Param("statuses") List<String> statuses);

    List<OsmSyncRun> findByTargetIdOrderByCreatedAtDesc(Long targetId);
}