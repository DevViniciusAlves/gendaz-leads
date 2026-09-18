package com.gendaz.leads.repository;

import com.gendaz.leads.entity.MessageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {

    @Query("SELECT m FROM MessageTemplate m WHERE m.isDefault = TRUE")
    Optional<MessageTemplate> findFirstByIsDefaultTrue();

    @Modifying
    @Query("UPDATE MessageTemplate m SET m.isDefault = FALSE")
    void updateDefaultFalse();

    @Modifying
    @Query("UPDATE MessageTemplate m SET m.isDefault = TRUE WHERE m.id = :id")
    void updateDefaultTrue(@Param("id") Long id);
}