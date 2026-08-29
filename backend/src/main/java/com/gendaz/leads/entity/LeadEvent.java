package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "lead_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_metadata", columnDefinition = "TEXT")
    private String eventMetadata;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
