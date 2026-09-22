package com.gendaz.leads.repository;

import com.gendaz.leads.entity.OsmPlace;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OsmPlaceRepository extends JpaRepository<OsmPlace, Long> {

    Optional<OsmPlace> findByOsmTypeAndOsmId(
            String osmType,
            Long osmId
    );

    Page<OsmPlace> findByRegionIdAndActiveTrue(
            Long regionId,
            Pageable pageable
    );

    List<OsmPlace> findByRegionIdAndActiveTrueOrderByNormalizedName(
            Long regionId
    );

    List<OsmPlace> findByRegionIdAndNormalizedNameContainingIgnoreCaseAndActiveTrue(
            Long regionId,
            String normalizedName
    );

    long countByRegionIdAndActiveTrue(Long regionId);
}