package com.gendaz.leads.dto.campaign;

import lombok.*;
import java.util.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendResultEnqueue {
    private List<MessageSentResult> sent;
    private List<MessageSkippedResult> skipped;
}