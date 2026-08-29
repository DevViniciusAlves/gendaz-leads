package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "leads")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "normalized_name")
    private String normalizedName;

    private String category;

    private String address;

    private String city;

    private String state;

    @Builder.Default
    private String country = "BR";

    private String phone;

    @Column(name = "normalized_phone")
    private String normalizedPhone;

    private String website;

    @Column(name = "normalized_website")
    private String normalizedWebsite;

    @Column(name = "instagram_username")
    private String instagramUsername;

    @Column(name = "normalized_instagram")
    private String normalizedInstagram;

    @Column(name = "instagram_url")
    private String instagramUrl;

    @Builder.Default
    @Column(name = "instagram_status")
    private String instagramStatus = "NOT_FOUND";

    private String source;

    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "normalized_source_id")
    private String normalizedSourceId;

    @Builder.Default
    @Column(name = "do_not_contact")
    private boolean doNotContact = false;

    @Builder.Default
    @Column(nullable = false)
    private String status = "NEW";

    @Column(name = "current_campaign_id")
    private Long currentCampaignId;

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
