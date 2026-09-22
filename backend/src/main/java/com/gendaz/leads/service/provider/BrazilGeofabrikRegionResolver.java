package com.gendaz.leads.service.provider;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

@Component
public class BrazilGeofabrikRegionResolver {

    private static final Map<String, String> STATE_TO_REGION = Map.ofEntries(
            Map.entry("distrito federal", "centro-oeste"),
            Map.entry("df", "centro-oeste"),
            Map.entry("goias", "centro-oeste"),
            Map.entry("goiás", "centro-oeste"),
            Map.entry("go", "centro-oeste"),
            Map.entry("mato grosso", "centro-oeste"),
            Map.entry("mt", "centro-oeste"),
            Map.entry("mato grosso do sul", "centro-oeste"),
            Map.entry("ms", "centro-oeste"),
            Map.entry("alagoas", "nordeste"),
            Map.entry("al", "nordeste"),
            Map.entry("bahia", "nordeste"),
            Map.entry("ba", "nordeste"),
            Map.entry("ceara", "nordeste"),
            Map.entry("ceará", "nordeste"),
            Map.entry("ce", "nordeste"),
            Map.entry("maranhao", "nordeste"),
            Map.entry("maranhão", "nordeste"),
            Map.entry("ma", "nordeste"),
            Map.entry("paraiba", "nordeste"),
            Map.entry("paraíba", "nordeste"),
            Map.entry("pb", "nordeste"),
            Map.entry("pernambuco", "nordeste"),
            Map.entry("pe", "nordeste"),
            Map.entry("piaui", "nordeste"),
            Map.entry("piauí", "nordeste"),
            Map.entry("pi", "nordeste"),
            Map.entry("rio grande do norte", "nordeste"),
            Map.entry("rn", "nordeste"),
            Map.entry("sergipe", "nordeste"),
            Map.entry("se", "nordeste"),
            Map.entry("acre", "norte"),
            Map.entry("ac", "norte"),
            Map.entry("amapa", "norte"),
            Map.entry("amapá", "norte"),
            Map.entry("ap", "norte"),
            Map.entry("amazonas", "norte"),
            Map.entry("am", "norte"),
            Map.entry("para", "norte"),
            Map.entry("pará", "norte"),
            Map.entry("pa", "norte"),
            Map.entry("rondonia", "norte"),
            Map.entry("rondônia", "norte"),
            Map.entry("ro", "norte"),
            Map.entry("roraima", "norte"),
            Map.entry("rr", "norte"),
            Map.entry("tocantins", "norte"),
            Map.entry("to", "norte"),
            Map.entry("espirito santo", "sudeste"),
            Map.entry("espírito santo", "sudeste"),
            Map.entry("es", "sudeste"),
            Map.entry("minas gerais", "sudeste"),
            Map.entry("mg", "sudeste"),
            Map.entry("rio de janeiro", "sudeste"),
            Map.entry("rj", "sudeste"),
            Map.entry("sao paulo", "sudeste"),
            Map.entry("são paulo", "sudeste"),
            Map.entry("sp", "sudeste"),
            Map.entry("parana", "sul"),
            Map.entry("paraná", "sul"),
            Map.entry("pr", "sul"),
            Map.entry("rio grande do sul", "sul"),
            Map.entry("rs", "sul"),
            Map.entry("santa catarina", "sul"),
            Map.entry("sc", "sul")
    );

    public String resolve(String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("Estado é obrigatório");
        }
        String normalized = normalize(state);
        String region = STATE_TO_REGION.get(normalized);
        if (region == null) {
            throw new IllegalArgumentException("GEOFABRIK_REGION_NOT_RESOLVED: Não foi possível mapear o estado '" + state + "' para uma região Geofabrik");
        }
        return region;
    }

    private String normalize(String input) {
        return java.text.Normalizer.normalize(input.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public String buildExtractUrl(String region) {
        return "https://download.geofabrik.de/south-america/brazil/" + region + "-latest.osm.pbf";
    }

    public String buildChecksumUrl(String region) {
        return "https://download.geofabrik.de/south-america/brazil/" + region + "-latest.osm.pbf.md5";
    }
}