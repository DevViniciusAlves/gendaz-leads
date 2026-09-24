package com.gendaz.leads.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "osm_places")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OsmPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private OsmCatalogRegion region;

    @Column(name = "osm_type", nullable = false, length = 10)
    private String osmType;

    @Column(name = "osm_id", nullable = false)
    private Long osmId;

    @Column(name = "business_name", length = 500)
    private String businessName;

    @Column(name = "normalized_name", length = 500)
    private String normalizedName;

    @Column(name = "latitude", nullable = false)
    private Double latitude;

    @Column(name = "longitude", nullable = false)
    private Double longitude;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "city", length = 255)
    private String city;

    @Column(name = "state", length = 255)
    private String state;

    @Column(name = "country", length = 255)
    private String country;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "phone", length = 100)
    private String phone;

    @Column(name = "normalized_phone", length = 32)
    private String normalizedPhone;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "website", length = 500)
    private String website;

    @Column(name = "instagram", length = 255)
    private String instagram;

    @Column(name = "normalized_instagram", length = 255)
    private String normalizedInstagram;

    @Column(name = "tags", columnDefinition = "JSONB")
    private String tags;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "qualified", nullable = false)
    @Builder.Default
    private Boolean qualified = false;

    @Column(name = "qualified_at", columnDefinition = "TIMESTAMPTZ")
    private Instant qualifiedAt;

    @Column(name = "whatsapp_verified", nullable = false)
    @Builder.Default
    private Boolean whatsappVerified = false;

    @Column(name = "whatsapp_verified_at", columnDefinition = "TIMESTAMPTZ")
    private Instant whatsappVerifiedAt;

    @Column(name = "instagram_validated", nullable = false)
    @Builder.Default
    private Boolean instagramValidated = false;

    @Column(name = "instagram_validated_at", columnDefinition = "TIMESTAMPTZ")
    private Instant instagramValidatedAt;

    @Column(name = "instagram_source", length = 100)
    private String instagramSource;

    @Column(name = "instagram_source_url", columnDefinition = "TEXT")
    private String instagramSourceUrl;

    @Column(name = "last_qualified_niche", length = 255)
    private String lastQualifiedNiche;

    @Column(name = "contact_status", length = 50)
    private String contactStatus;

    @Column(name = "contact_source", length = 100)
    private String contactSource;

    @Column(name = "contact_source_url", columnDefinition = "TEXT")
    private String contactSourceUrl;

    @Column(name = "enriched_at", columnDefinition = "TIMESTAMPTZ")
    private Instant enrichedAt;

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