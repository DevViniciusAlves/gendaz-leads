package com.gendaz.leads.service;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.HashSet;

@Service
public class TemplateRenderer {

    private static final int MAX_LENGTH = 4000;
    private static final Set<String> ALLOWED_VARS = Set.of("{{nome}}", "{{cidade}}", "{{nicho}}", "{{instagram}}");
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{[^}]+\\}\\}");

    public void validateTemplate(String templateText) {
        if (templateText == null || templateText.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE", "O template não pode ser vazio.");
        }
        if (templateText.length() > MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE_TOO_LONG", "Template excede o limite de " + MAX_LENGTH + " caracteres.");
        }

        Matcher matcher = VAR_PATTERN.matcher(templateText);
        while (matcher.find()) {
            String var = matcher.group();
            if (!ALLOWED_VARS.contains(var)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE_VARIABLE", "Variável desconhecida no template: " + var);
            }
        }
    }

    public String render(String templateText, Lead lead, Campaign campaign) {
        validateTemplate(templateText);

        String result = templateText;

        result = replaceVariable(result, "{{nome}}", lead.getBusinessName() != null ? lead.getBusinessName() : "");
        result = replaceVariable(result, "{{cidade}}", lead.getCity() != null ? lead.getCity() : "");
        result = replaceVariable(result, "{{nicho}}", campaign != null ? campaign.getNiche() : "");
        result = replaceVariable(result, "{{instagram}}", lead.getInstagramUsername() != null ? lead.getInstagramUsername() : "");

        if (result.length() > MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEMPLATE_TOO_LONG", "Template renderizado excede o limite de " + MAX_LENGTH + " caracteres.");
        }

        return result;
    }

    private String replaceVariable(String text, String variable, String value) {
        if (value == null) {
            value = "";
        }
        return text.replace(variable, value);
    }
}
