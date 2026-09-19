package com.gendaz.leads.dto.campaign;

import java.util.List;

public record SendPreviewResponse(
    int eligibleCount,
    int ineligibleCount,
    List<PreviewItem> previews,
    List<IneligibleLeadResponse> ineligible
) {}
