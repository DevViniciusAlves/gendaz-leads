package com.gendaz.leads.dto.campaign;

import lombok.*;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignMessagingLeadResponse {
    private Long leadId;
    private String businessName;
    private String category;
    private String instagramUsername;
    private String instagramUrl;
    private String phone;
    private String city;
    private String state;
    private String website;
    private Integer opportunityScore;
    private String detectedSystem;
    private String leadStatus;
    private boolean eligible;
    private String ineligibilityCode;
    private String ineligibilityReason;
    private Long messageSendId;
    private String sendStatus;
    private Integer attempts;
    private Instant queuedAt;
    private Instant sentAt;
    private String errorCode;
}