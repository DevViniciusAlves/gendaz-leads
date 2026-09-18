package com.gendaz.leads.dto.campaign;

import lombok.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageSkippedResult {
    private Long leadId;
    private String businessName;
    private String reason;
}