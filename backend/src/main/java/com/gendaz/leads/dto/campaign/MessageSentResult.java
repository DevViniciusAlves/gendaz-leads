package com.gendaz.leads.dto.campaign;

import lombok.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageSentResult {
    private Long messageSendId;
    private String businessName;
    private String phone;
    private String status;
}