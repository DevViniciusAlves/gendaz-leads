package com.gendaz.leads.repository;

import com.gendaz.leads.entity.Campaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    Page<Campaign> findByOwnerId(Long ownerId, Pageable pageable);

    List<Campaign> findByOwnerIdAndStatus(Long ownerId, String status);

    long countByOwnerIdAndStatusIn(Long ownerId, List<String> statuses);

    @Query("SELECT c FROM Campaign c WHERE c.id = :id")
    @org.springframework.data.jpa.repository.Lock(org.springframework.data.jpa.repository.LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<Campaign> findByIdForUpdate(@Param("id") Long id);

}
