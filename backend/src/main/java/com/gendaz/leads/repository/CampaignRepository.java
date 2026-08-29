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

    @Query("SELECT c FROM Campaign c WHERE c.ownerId = :ownerId AND " +
            "LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY c.createdAt DESC")
    List<Campaign> searchByOwner(@Param("ownerId") Long ownerId, @Param("q") String q);
}
