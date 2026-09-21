package com.gendaz.leads.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

public final class CountryCodeResolver {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("brazil", "br"),
            Map.entry("brasil", "br"),
            Map.entry("br", "br"),
            Map.entry("united states", "us"),
            Map.entry("estados unidos", "us"),
            Map.entry("us", "us"),
            Map.entry("usa", "us"),
            Map.entry("eua", "us"),
            Map.entry("united states of america", "us"),
            Map.entry("portugal", "pt"),
            Map.entry("argentina", "ar"),
            Map.entry("germany", "de"),
            Map.entry("alemanha", "de"),
            Map.entry("uk", "gb"),
            Map.entry("france", "fr"),
            Map.entry("frança", "fr"),
            Map.entry("spain", "es"),
            Map.entry("espanha", "es"),
            Map.entry("italy", "it"),
            Map.entry("itália", "it"),
            Map.entry("canada", "ca"),
            Map.entry("canadá", "ca"),
            Map.entry("mexico", "mx"),
            Map.entry("méxico", "mx"),
            Map.entry("colombia", "co"),
            Map.entry("colômbia", "co"),
            Map.entry("chile", "cl"),
            Map.entry("peru", "pe"),
            Map.entry("perú", "pe"),
            Map.entry("uruguay", "uy"),
            Map.entry("uruguai", "uy"),
            Map.entry("paraguay", "py"),
            Map.entry("paraguai", "py"),
            Map.entry("bolivia", "bo"),
            Map.entry("bolívia", "bo"),
            Map.entry("ecuador", "ec"),
            Map.entry("venezuela", "ve")
    );

    private static final Map<String, String> DISPLAY_NAMES_PT_BR = Map.ofEntries(
            Map.entry("br", "Brasil"),
            Map.entry("us", "Estados Unidos"),
            Map.entry("pt", "Portugal"),
            Map.entry("ar", "Argentina"),
            Map.entry("de", "Alemanha"),
            Map.entry("gb", "Reino Unido"),
            Map.entry("fr", "França"),
            Map.entry("es", "Espanha"),
            Map.entry("it", "Itália"),
            Map.entry("ca", "Canadá"),
            Map.entry("mx", "México"),
            Map.entry("co", "Colômbia"),
            Map.entry("cl", "Chile"),
            Map.entry("pe", "Peru"),
            Map.entry("uy", "Uruguai"),
            Map.entry("py", "Paraguai"),
            Map.entry("bo", "Bolívia"),
            Map.entry("ec", "Equador"),
            Map.entry("ve", "Venezuela")
    );

    private static final Set<String> VALID_ISO2_CODES = Set.of(
            "ad", "ae", "af", "ag", "ai", "al", "am", "ao", "aq", "ar", "as", "at", "au", "aw", "ax", "az",
            "ba", "bb", "bd", "be", "bf", "bg", "bh", "bi", "bj", "bl", "bm", "bn", "bo", "bq", "br", "bs", "bt", "bv", "bw", "by", "bz",
            "ca", "cc", "cd", "cf", "cg", "ch", "ci", "ck", "cl", "cm", "cn", "co", "cr", "cu", "cv", "cw", "cx", "cy", "cz",
            "de", "dj", "dk", "dm", "do", "dz",
            "ec", "ee", "eg", "eh", "er", "es", "et",
            "fi", "fj", "fk", "fm", "fo", "fr",
            "ga", "gb", "gd", "ge", "gf", "gg", "gh", "gi", "gl", "gm", "gn", "gp", "gq", "gr", "gs", "gt", "gu", "gw", "gy",
            "hk", "hm", "hn", "hr", "ht", "hu",
            "id", "ie", "il", "im", "in", "io", "iq", "ir", "is", "it",
            "je", "jm", "jo", "jp",
            "ke", "kg", "kh", "ki", "km", "kn", "kp", "kr", "kw", "ky", "kz",
            "la", "lb", "lc", "li", "lk", "lr", "ls", "lt", "lu", "lv", "ly",
            "ma", "mc", "md", "me", "mf", "mg", "mh", "mk", "ml", "mm", "mn", "mo", "mp", "mq", "mr", "ms", "mt", "mu", "mv", "mw", "mx", "my", "mz",
            "na", "nc", "ne", "nf", "ng", "ni", "nl", "no", "np", "nr", "nu", "nz",
            "om",
            "pa", "pe", "pf", "pg", "ph", "pk", "pl", "pm", "pn", "pr", "ps", "pt", "pw", "py",
            "qa",
            "re", "ro", "rs", "ru", "rw",
            "sa", "sb", "sc", "sd", "se", "sg", "sh", "si", "sj", "sk", "sl", "sm", "sn", "so", "sr", "ss", "st", "sv", "sx", "sy", "sz",
            "tc", "td", "tf", "tg", "th", "tj", "tk", "tl", "tm", "tn", "to", "tr", "tt", "tv", "tw", "tz",
            "ua", "ug", "um", "us", "uy", "uz",
            "va", "vc", "ve", "vg", "vi", "vn", "vu",
            "wf", "ws",
            "ye", "yt",
            "za", "zm", "zw"
    );

    private CountryCodeResolver() {}

    public static String resolveToIso2(String value) {
        if (value == null || value.isBlank()) {
            throw invalidCountry("País é obrigatório.");
        }
        String normalized = normalizeInput(value);
        String alias = ALIASES.get(normalized);
        if (alias != null) {
            return alias;
        }
        if (VALID_ISO2_CODES.contains(normalized)) {
            return normalized;
        }
        throw invalidCountry("País inválido: " + value);
    }

    public static String displayNamePtBr(String iso2) {
        if (iso2 == null || iso2.isBlank()) {
            return "";
        }
        return DISPLAY_NAMES_PT_BR.getOrDefault(iso2.toLowerCase(Locale.ROOT), iso2.toUpperCase(Locale.ROOT));
    }

    public static List<CountryOption> allCountriesPtBr() {
        return DISPLAY_NAMES_PT_BR.entrySet().stream()
                .map(e -> new CountryOption(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(CountryOption::name))
                .collect(Collectors.toList());
    }

    public record CountryOption(String code, String name) {}

    private static String normalizeInput(String input) {
        String noAccents = java.text.Normalizer.normalize(input.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static ResponseStatusException invalidCountry(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message, null);
    }
}