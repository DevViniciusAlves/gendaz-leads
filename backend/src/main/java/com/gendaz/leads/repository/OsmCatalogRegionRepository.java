package com.gendaz.leads.repository;

import com.gendaz.leads.entity.OsmCatalogRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OsmCatalogRegionRepository extends JpaRepository<OsmCatalogRegion, Long> {

    Optional<OsmCatalogRegion> findByNormalizedCityAndNormalizedStateAndCountryCode(
            String normalizedCity, String normalizedState, String countryCode);

    Optional<OsmCatalogRegion> findByOsmTypeAndOsmId(String osmType, Long osmId);

    List<OsmCatalogRegion> findByCatalogStatus(String catalogStatus);

    List<OsmCatalogRegion> findByCountryCode(String countryCode);

    @Query("SELECT r FROM OsmCatalogRegion r WHERE r.catalogStatus = 'READY' AND r.countryCode = :countryCode")
    List<OsmCatalogRegion> findReadyByCountryCode(@Param("countryCode") String countryCode);

    @Query("SELECT r FROM OsmCatalogRegion r WHERE r.normalizedCity = :normalizedCity AND r.countryCode = :countryCode AND r.catalogStatus = 'READY'")
    List<OsmCatalogRegion> findByNormalizedCityAndCountryCodeAndCatalogStatus(
            @Param("normalizedCity") String normalizedCity,
            @Param("countryCode") String countryCode,
            @Param("catalogStatus") String catalogStatus);
}