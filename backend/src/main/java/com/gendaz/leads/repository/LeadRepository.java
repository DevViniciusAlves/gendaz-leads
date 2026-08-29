package com.gendaz.leads.repository;

import com.gendaz.leads.entity.Lead;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    Page<Lead> findByDoNotContactIsFalse(Pageable pageable);

    List<Lead> findByCurrentCampaignId(Long campaignId);

    @Query("SELECT l FROM Lead l JOIN CampaignLead cl ON cl.leadId = l.id " +
            "WHERE cl.campaignId = :campaignId ORDER BY l.createdAt DESC")
    List<Lead> findByCampaign(@Param("campaignId") Long campaignId);

    Optional<Lead> findFirstByNormalizedInstagramIgnoreCase(String normalizedInstagram);
    Optional<Lead> findFirstByNormalizedWebsiteIgnoreCase(String normalizedWebsite);
    Optional<Lead> findFirstByNormalizedPhoneIgnoreCase(String normalizedPhone);
    Optional<Lead> findFirstByNormalizedSourceIdIgnoreCase(String normalizedSourceId);
    Optional<Lead> findFirstByNormalizedNameAndCityAndStateIgnoreCase(String normalizedName, String city, String state);

    boolean existsByNormalizedInstagramIgnoreCase(String normalizedInstagram);
    boolean existsByNormalizedWebsiteIgnoreCase(String normalizedWebsite);
    boolean existsByNormalizedPhoneIgnoreCase(String normalizedPhone);
    boolean existsByNormalizedSourceIdIgnoreCase(String normalizedSourceId);

    @Query("SELECT l.status, COUNT(l) FROM Lead l GROUP BY l.status")
    List<Object[]> countByStatus();

    @Query("SELECT COUNT(l) FROM Lead l JOIN CampaignLead cl ON cl.leadId = l.id JOIN Campaign c ON c.id = cl.campaignId WHERE l.id = :leadId AND c.ownerId = :ownerId")
    long countOwnedByUser(@Param("leadId") Long leadId, @Param("ownerId") Long ownerId);

    @Query("SELECT COUNT(l) FROM Lead l JOIN CampaignLead cl ON cl.leadId = l.id JOIN Campaign c ON c.id = cl.campaignId WHERE c.ownerId = :ownerId")
    long countOwnedTotal(@Param("ownerId") Long ownerId);

    @Query("SELECT l.status, COUNT(l) FROM Lead l JOIN CampaignLead cl ON cl.leadId = l.id JOIN Campaign c ON c.id = cl.campaignId WHERE c.ownerId = :ownerId GROUP BY l.status")
    List<Object[]> countOwnedByStatus(@Param("ownerId") Long ownerId);
}
