package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "campaign_leads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignLead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "added_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant addedAt;

    @PrePersist
    void onCreate() {
        this.addedAt = Instant.now();
    }
}
