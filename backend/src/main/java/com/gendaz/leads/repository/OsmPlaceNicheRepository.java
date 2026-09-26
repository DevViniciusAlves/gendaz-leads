package com.gendaz.leads.repository;

import com.gendaz.leads.entity.OsmPlaceNiche;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OsmPlaceNicheRepository extends JpaRepository<OsmPlaceNiche, Long> {

    Optional<OsmPlaceNiche> findByPlaceIdAndTargetId(Long placeId, Long targetId);

    List<OsmPlaceNiche> findByTargetIdAndActiveTrue(Long targetId);

    @Query("SELECT COUNT(n) FROM OsmPlaceNiche n WHERE n.target.id = :targetId AND n.active = true")
    long countActiveByTargetId(@Param("targetId") Long targetId);
}
