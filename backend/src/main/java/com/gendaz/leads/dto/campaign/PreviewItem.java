package com.gendaz.leads.dto.campaign;

import lombok.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PreviewItem {
    private Long leadId;
    private String businessName;
    private String instagram;
    private String phone;
    private String location;
    private String previewMessage;
}