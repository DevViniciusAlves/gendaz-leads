package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "campaigns")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String niche;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false, length = 120)
    private String country;

    @Column(nullable = false)
    private String location;

    @Column(name = "requested_quantity", nullable = false)
    private int requestedQuantity;

    @Column(nullable = false)
    @Builder.Default
    private String status = "CREATED";

    @Column(name = "discovered_count")
    @Builder.Default
    private int discoveredCount = 0;

    @Column(name = "analyzed_count")
    @Builder.Default
    private int analyzedCount = 0;

    @Column(name = "message_count")
    @Builder.Default
    private int messageCount = 0;

    @Column(name = "approved_count")
    @Builder.Default
    private int approvedCount = 0;

    @Column(name = "sent_count")
    @Builder.Default
    private int sentCount = 0;

    @Column(name = "replied_count")
    @Builder.Default
    private int repliedCount = 0;

    @Column(name = "interested_count")
    @Builder.Default
    private int interestedCount = 0;

    @Column(name = "converted_count")
    @Builder.Default
    private int convertedCount = 0;

    @Column(name = "blocked_count")
    @Builder.Default
    private int blockedCount = 0;

    @Column(name = "progress_stage")
    private String progressStage;

    @Column(name = "progress_current")
    @Builder.Default
    private int progressCurrent = 0;

    @Column(name = "progress_total")
    @Builder.Default
    private int progressTotal = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
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