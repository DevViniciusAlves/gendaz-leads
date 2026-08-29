package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "message_sends")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageSend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(nullable = false)
    private String provider;

    @Builder.Default
    @Column(nullable = false)
    private String status = "QUEUED";

    @Builder.Default
    private int attempts = 0;

    @Column(name = "last_attempt_at", columnDefinition = "TIMESTAMPTZ")
    private Instant lastAttemptAt;

    @Column(name = "next_attempt_at", columnDefinition = "TIMESTAMPTZ")
    private Instant nextAttemptAt;

    @Column(columnDefinition = "TEXT")
    private String result;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
