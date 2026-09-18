package com.gendaz.leads.dto.campaign;

import lombok.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendPreviewRequest {
    private List<Long> leadIds;
    private String templateText;
    private Boolean allEligible;
    private Long templateId;

    public boolean isAllEligible() {
        return allEligible != null && allEligible;
    }
}