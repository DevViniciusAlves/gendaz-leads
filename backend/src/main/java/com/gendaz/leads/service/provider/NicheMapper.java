package com.gendaz.leads.service.provider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapeia o nicho informado pelo usuario (pt-BR, com/sem acento) para
 * filtros estruturados do OpenStreetMap (tag=valor).
 *
 * <p>Nunca depende de acentos: a chave e normalizada antes da busca.
 */
public final class NicheMapper {

    private NicheMapper() {
    }

    public record NicheStrategy(List<String> tagFilters, String fallbackNameRegex) {
    }

    private static final Map<String, List<String>> ALIAS_TO_TAGS = new HashMap<>();

    static {
        // beleza / estetica
        put(List.of("estetica", "esthetica", "clinica de estetica", "centro de estetica",
                "beleza", "estudio de beleza", "aesthetic", "aesthetics"),
                List.of("shop=beauty", "shop=beauty,beauty=aesthetic", "shop=beauty,beauty=skin_care"));
        // cilios
        put(List.of("cilios", "cilio", "extensao de cilios", "alongamento de cilios",
                "lash", "lashes", "lash designer", "lash design", "cilista"),
                List.of("shop=beauty,beauty=eyelash", "shop=beauty,beauty=nails,beauty=eyelash"));
        // sobrancelhas
        put(List.of("sobrancelha", "sobrancelhas", "designer de sobrancelha",
                "design de sobrancelha", "brow", "brows", "eyebrow", "eyebrows"),
                List.of("shop=beauty,beauty=eyebrow"));
        // unhas
        put(List.of("unha", "unhas", "manicure", "pedicure", "nail", "nails",
                "nail designer", "esmalteria", "alongamento de unhas"),
                List.of("shop=beauty,beauty=nails"));
        // salao / cabelo
        put(List.of("salao", "salao de beleza", "salao de cabelereiro", "beleza salao",
                "cabeleireiro", "cabeleireira", "cabelo", "cabelos", "cabelereiro",
                "hair", "hairdresser", "hair salon", "coiffeur"),
                List.of("shop=hairdresser", "shop=beauty"));
        // barbearia - usa estrategia composta: shop=barber E/OU hairdresser=barber
        put(List.of("barbearia", "barbearias", "barber", "barbershop", "barba"),
                List.of("shop=barber", "shop=hairdresser,hairdresser=barber"));
        // dentista / odonto
        put(List.of("dentista", "dentistas", "odontologia", "clinica odontologica",
                "consultorio odontologico", "dentist", "dental"),
                List.of("amenity=dentist"));
        // clinica generica
        put(List.of("clinica", "clinicas", "clinica medica", "consultorio",
                "consultorio medico", "medico", "saude"),
                List.of("amenity=clinic", "amenity=doctors"));
        // massagem / spa
        put(List.of("massagem", "massoterapia", "massagista", "spa", "terapia",
                "massage", "fisioterapia", "pilates"),
                List.of("shop=beauty,beauty=massage", "leisure=spa", "shop=massage"));
        // depilacao
        put(List.of("depilacao", "depiladora", "epilacao", "hair removal",
                "laser", "depilacao a laser"),
                List.of("shop=beauty,beauty=hair_removal"));
        // genericos de beleza (fallback para qualquer outro termo de beleza)
        put(List.of("estudio", "beauty", "skincare", "skin care", "maquiagem", "makeup"),
                List.of("shop=beauty"));
        // academia
        put(List.of("academia", "academias", "fitness", "crossfit", "musculacao"),
                List.of("leisure=fitness_centre"));
        // restaurantes etc (mantem compatibilidade com mapa antigo)
        put(List.of("restaurante", "restaurantes", "comida", "lanchonete"),
                List.of("amenity=restaurant"));
        put(List.of("cafe", "cafeteria", "cafeteria"),
                List.of("amenity=cafe"));
        put(List.of("padaria", "panificadora"), List.of("shop=bakery"));
        put(List.of("hotel", "hoteis", "pousada", "hospedagem"), List.of("tourism=hotel"));
        put(List.of("pet", "petshop", "banho e tosa"), List.of("shop=pet"));
        put(List.of("veterinario", "veterinaria", "vet"), List.of("amenity=veterinary"));
        put(List.of("farmacia", "drogaria"), List.of("amenity=pharmacy"));
        put(List.of("advogado", "advocacia", "advogados"), List.of("office=lawyer"));
    }

    private static void put(List<String> aliases, List<String> tags) {
        for (String alias : aliases) {
            ALIAS_TO_TAGS.put(normalizeKey(alias), tags);
        }
    }

    /** Normaliza sem acento, minusculo e espacos colapsados. */
    public static String normalizeKey(String input) {
        if (input == null) return "";
        String s = java.text.Normalizer.normalize(input.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Resolve a estrategia de busca para um nicho livre. Nunca retorna null. */
    public static NicheStrategy resolve(String niche) {
        String key = normalizeKey(niche);
        List<String> tags = ALIAS_TO_TAGS.get(key);
        if (tags == null) {
            // tenta match parcial: se a chave contem um alias conhecido, usa aquelas tags
            for (Map.Entry<String, List<String>> e : ALIAS_TO_TAGS.entrySet()) {
                if (!e.getKey().isBlank() && key.contains(e.getKey())) {
                    tags = e.getValue();
                    break;
                }
            }
        }
        if (tags == null) tags = List.of();
        return new NicheStrategy(tags, sanitizeForRegex(niche));
    }

    /**
     * Sanitiza o termo do usuario para uso dentro de query Overpass.
     * Remove caracteres especiais de regex e limita o tamanho.
     * NÃO usa Pattern.quote() (gera \\Q\\E incompativel com Overpass).
     * Para nichos conhecidos, usa aliases regex explicitos.
     */
    static String sanitizeForRegex(String niche) {
        if (niche == null) return "";
        String s = niche.trim();
        if (s.length() > 60) s = s.substring(0, 60);
        // mantem letras (incl. acentuadas), numeros e espacos; resto vira espaco
        s = s.replaceAll("[^\\p{L}0-9 ]", " ").replaceAll("\\s+", " ").trim();
        // Nao usa Pattern.quote. Para texto literal no Overpass, usar aliases
        // explicitos quando possivel. Aqui apenas limpamos para uso seguro.
        return s;
    }
}