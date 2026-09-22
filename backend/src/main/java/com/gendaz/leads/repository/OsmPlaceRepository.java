package com.gendaz.leads.repository;

import com.gendaz.leads.entity.OsmPlace;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OsmPlaceRepository extends JpaRepository<OsmPlace, Long> {

    Optional<OsmPlace> findByOsmTypeAndOsmId(String osmType, Long osmId);

    Page<OsmPlace> findByRegionIdAndActiveTrue(Long regionId, Pageable pageable);

    List<OsmPlace> findByRegionIdAndActiveTrueOrderByNormalizedName(Long regionId);

    List<OsmPlace> findByRegionIdAndNormalizedNameContainingIgnoreCaseAndActiveTrue(
            Long regionId, String normalizedName);

    @Query("SELECT p FROM OsmPlace p WHERE p.region.id = :regionId AND p.active = true " +
           "AND (p.tags::jsonb ? :key OR p.normalizedName ILIKE %:name%)")
    List<OsmPlace> findByRegionIdAndTagKeyOrName(
            @Param("regionId") Long regionId,
            @Param("key") String key,
            @Param("name") String name);

    long countByRegionIdAndActiveTrue(Long regionId);

    @Query("SELECT p FROM OsmPlace p WHERE p.region.id = :regionId AND p.active = true " +
           "AND p.tags::jsonb @> :tags")
    List<OsmPlace> findByRegionIdAndTags(
            @Param("regionId") Long regionId,
            @Param("tags") String tagsJson);
}