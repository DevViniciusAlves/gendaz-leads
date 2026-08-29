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
    private String location;

    @Column(name = "requested_quantity", nullable = false)
    private int requestedQuantity;

    @Column(nullable = false)
    @Builder.Default
    private String status = "CREATED";

    @Builder.Default
    @Column(name = "discovered_count")
    private int discoveredCount = 0;

    @Builder.Default
    @Column(name = "analyzed_count")
    private int analyzedCount = 0;

    @Builder.Default
    @Column(name = "message_count")
    private int messageCount = 0;

    @Builder.Default
    @Column(name = "approved_count")
    private int approvedCount = 0;

    @Builder.Default
    @Column(name = "sent_count")
    private int sentCount = 0;

    @Builder.Default
    @Column(name = "replied_count")
    private int repliedCount = 0;

    @Builder.Default
    @Column(name = "interested_count")
    private int interestedCount = 0;

    @Builder.Default
    @Column(name = "converted_count")
    private int convertedCount = 0;

    @Builder.Default
    @Column(name = "blocked_count")
    private int blockedCount = 0;

    @Column(name = "progress_stage")
    private String progressStage;

    @Builder.Default
    @Column(name = "progress_current")
    private int progressCurrent = 0;

    @Builder.Default
    @Column(name = "progress_total")
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
