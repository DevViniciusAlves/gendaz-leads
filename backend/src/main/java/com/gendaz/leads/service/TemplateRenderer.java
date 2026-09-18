package com.gendaz.leads.service;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.entity.Campaign;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TemplateRenderer {

    @Transactional
    public String render(String templateText, Lead lead, Campaign campaign) {
        if (templateText == null || templateText.isBlank()) {
            return "";
        }
        String result = templateText;

        // Substituir {{nome}} -> businessName do Lead
        result = replaceVariable(result, "{{nome}}",
                lead.getBusinessName() != null ? lead.getBusinessName() : "");

        // Substituir {{cidade}} -> city do Lead
        result = replaceVariable(result, "{{cidade}}",
                lead.getCity() != null ? lead.getCity() : "");

        // Substituir {{nicho}} -> niche da Campaign
        result = replaceVariable(result, "{{nicho}}",
                campaign != null ? campaign.getNiche() : "");

        // Substituir {{instagram}} -> instagramUsername do Lead
        result = replaceVariable(result, "{{instagram}}",
                lead.getInstagramUsername() != null ? lead.getInstagramUsername() : "");

        // Limpar eventuais double-spaces ou trailing
        result = result.replaceAll("\\n\\n+", "\n").trim();

        return result;
    }

    private String replaceVariable(String text, String variable, String value) {
        String marker = variable;
        if (value == null) {
            value = "";
        }
        return text.replace(marker, value);
    }
}