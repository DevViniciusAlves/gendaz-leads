package com.gendaz.leads.dto.campaign;

import lombok.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendPreviewResponse {
    private int eligibleCount;
    private int ineligibleCount;
    private List<PreviewItem> previews;
    private List<String> ineligibilityReasons;
}