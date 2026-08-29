package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "lead_analysis")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lead_id", nullable = false, unique = true)
    private Long leadId;

    @Column(name = "business_type")
    private String businessType;

    @Column(columnDefinition = "TEXT")
    private String services;

    @Column(name = "digital_presence")
    private String digitalPresence;

    @Column(name = "uses_booking_system")
    private Boolean usesBookingSystem;

    @Builder.Default
    @Column(name = "booking_system_status")
    private String bookingSystemStatus = "UNKNOWN";

    @Column(name = "detected_system")
    private String detectedSystem;

    @Column(name = "manual_attendance_signals", columnDefinition = "TEXT")
    private String manualAttendanceSignals;

    @Column(name = "pain_points", columnDefinition = "TEXT")
    private String painPoints;

    @Column(name = "commercial_opportunity", columnDefinition = "TEXT")
    private String commercialOpportunity;

    @Column(name = "opportunity_score")
    private Integer opportunityScore;

    @Column(name = "reasoning_summary", columnDefinition = "TEXT")
    private String reasoningSummary;

    private String model;

    @Column(name = "analyzed_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private Instant analyzedAt;

    @PrePersist
    void onCreate() {
        this.analyzedAt = Instant.now();
    }
}
