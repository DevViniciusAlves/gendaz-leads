package com.gendaz.leads.repository;

import com.gendaz.leads.entity.Campaign;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    Page<Campaign> findByOwnerId(Long ownerId, Pageable pageable);

    List<Campaign> findByOwnerIdAndStatus(Long ownerId, String status);

    long countByOwnerIdAndStatusIn(Long ownerId, List<String> statuses);

    @Query("SELECT c FROM Campaign c WHERE c.ownerId = :ownerId AND " +
            "LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) ORDER BY c.createdAt DESC")
    List<Campaign> searchByOwner(@Param("ownerId") Long ownerId, @Param("q") String q);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Campaign c WHERE c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") Long id);

}
