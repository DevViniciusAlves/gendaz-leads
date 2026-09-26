package com.gendaz.leads.repository;

import com.gendaz.leads.entity.OsmCatalogTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OsmCatalogTargetRepository extends JpaRepository<OsmCatalogTarget, Long> {

    Optional<OsmCatalogTarget> findByRegionIdAndCanonicalNiche(Long regionId, String canonicalNiche);

    List<OsmCatalogTarget> findByRegionIdOrderByCanonicalNicheAsc(Long regionId);

    List<OsmCatalogTarget> findAllByOrderByUpdatedAtDesc();
}
