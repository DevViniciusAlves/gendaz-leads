package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    public enum MatchMode {
        EXACT,
        SEMICOLON_TOKEN
    }

    public record TagCondition(
            String key,
            MatchMode mode,
            List<String> acceptedValues
    ) {
        public TagCondition {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "TagCondition key is required"
                );
            }

            if (mode == null) {
                throw new IllegalArgumentException(
                        "TagCondition mode is required"
                );
            }

            acceptedValues =
                    acceptedValues == null
                            ? List.of()
                            : acceptedValues.stream()
                            .filter(v ->
                                    v != null
                                            && !v.isBlank()
                            )
                            .map(v ->
                                    v.trim()
                                            .toLowerCase(
                                                    java.util.Locale.ROOT
                                            )
                            )
                            .distinct()
                            .toList();

            if (acceptedValues.isEmpty()) {
                throw new IllegalArgumentException(
                        "TagCondition values are required"
                );
            }

            key = key.trim();
        }
    }

    public record NicheRule(
            List<TagCondition> allOf
    ) {
        public NicheRule {
            allOf =
                    allOf == null
                            ? List.of()
                            : List.copyOf(allOf);

            if (allOf.isEmpty()) {
                throw new IllegalArgumentException(
                        "NicheRule requires conditions"
                );
            }
        }
    }

    public record NameFallback(
            List<NicheRule> contextAnyOf,
            List<String> aliases
    ) {
        public NameFallback {
            contextAnyOf =
                    contextAnyOf == null
                            ? List.of()
                            : List.copyOf(contextAnyOf);

            aliases =
                    aliases == null
                            ? List.of()
                            : aliases.stream()
                            .filter(v ->
                                    v != null
                                            && !v.isBlank()
                            )
                            .map(
                                    NicheMapper::normalizeNamePhrase
                            )
                            .filter(v -> !v.isBlank())
                            .distinct()
                            .toList();
        }

        public boolean enabled() {
            return !aliases.isEmpty();
        }

        public boolean requiresContext() {
            return !contextAnyOf.isEmpty();
        }
    }

    public record NicheStrategy(
            String canonicalName,
            List<NicheRule> structuredRules,
            NameFallback nameFallback
    ) {
        public NicheStrategy {
            canonicalName =
                    canonicalName == null
                            ? ""
                            : canonicalName.trim();

            structuredRules =
                    structuredRules == null
                            ? List.of()
                            : List.copyOf(structuredRules);

            nameFallback =
                    nameFallback == null
                            ? new NameFallback(
                                    List.of(),
                                    List.of()
                            )
                            : nameFallback;
        }
    }

    private static final Map<
            String,
            NicheStrategy
            > ALIAS_TO_STRATEGY =
            new HashMap<>();

    private static TagCondition exact(
            String key,
            String... values
    ) {
        return new TagCondition(
                key,
                MatchMode.EXACT,
                List.of(values)
        );
    }

    private static TagCondition token(
            String key,
            String... values
    ) {
        return new TagCondition(
                key,
                MatchMode.SEMICOLON_TOKEN,
                List.of(values)
        );
    }

    private static NicheRule rule(
            TagCondition... conditions
    ) {
        return new NicheRule(
                List.of(conditions)
        );
    }

    private static NameFallback fallback(
            List<NicheRule> contexts,
            String... aliases
    ) {
        return new NameFallback(
                contexts,
                List.of(aliases)
        );
    }

    static String normalizeNamePhrase(
            String input
    ) {
        if (input == null) {
            return "";
        }

        return java.text.Normalizer.normalize(
                        input
                                .trim()
                                .toLowerCase(
                                        java.util.Locale.ROOT
                                ),
                        java.text.Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}", "")
                .replaceAll(
                        "[^\\p{L}0-9]+",
                        " "
                )
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Normaliza sem acento, minusculo e espacos colapsados. */
    public static String normalizeKey(String input) {
        if (input == null) return "";
        String s = java.text.Normalizer.normalize(input.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static void register(
            String canonicalName,
            List<String> aliases,
            List<NicheRule> structuredRules,
            NameFallback fallback
    ) {
        NicheStrategy strategy =
                new NicheStrategy(
                        canonicalName,
                        structuredRules,
                        fallback
                );

        for (String alias : aliases) {
            ALIAS_TO_STRATEGY.put(
                    normalizeKey(alias),
                    strategy
            );
        }
    }

    static {
        // A.1 — Estética / beleza
        register(
                "beauty",
                List.of(
                        "estetica",
                        "esthetica",
                        "clinica de estetica",
                        "centro de estetica",
                        "beleza",
                        "estudio de beleza",
                        "aesthetic",
                        "aesthetics"
                ),
                List.of(
                        rule(
                                exact("shop", "beauty")
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("shop", "beauty"))
                        ),
                        "estetica",
                        "esthetica",
                        "clinica de estetica",
                        "centro de estetica",
                        "beleza",
                        "estudio de beleza",
                        "aesthetic",
                        "aesthetics"
                )
        );

        // A.2 — Cílios
        register(
                "eyelash",
                List.of(
                        "cilios",
                        "cilio",
                        "extensao de cilios",
                        "alongamento de cilios",
                        "lash",
                        "lashes",
                        "lash designer",
                        "lash design",
                        "cilista"
                ),
                List.of(
                        rule(
                                exact("shop", "beauty"),
                                token("beauty", "eyelash")
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("shop", "beauty")),
                                rule(exact("shop", "hairdresser"))
                        ),
                        "cilios",
                        "cilio",
                        "extensao de cilios",
                        "alongamento de cilios",
                        "lash",
                        "lashes",
                        "lash designer",
                        "lash design",
                        "cilista"
                )
        );

        // A.3 — Sobrancelha
        register(
                "eyebrow",
                List.of(
                        "sobrancelha",
                        "sobrancelhas",
                        "designer de sobrancelha",
                        "design de sobrancelha",
                        "brow",
                        "brows",
                        "eyebrow",
                        "eyebrows"
                ),
                List.of(
                        rule(
                                exact("shop", "beauty"),
                                token("beauty", "eyebrow")
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("shop", "beauty")),
                                rule(exact("shop", "hairdresser"))
                        ),
                        "sobrancelha",
                        "sobrancelhas",
                        "designer de sobrancelha",
                        "design de sobrancelha",
                        "brow",
                        "brows",
                        "eyebrow",
                        "eyebrows"
                )
        );

        // A.4 — Nails
        List<String> nailAliases =
                List.of(
                        "unha",
                        "unhas",
                        "manicure",
                        "pedicure",
                        "nail",
                        "nails",
                        "nail designer",
                        "esmalteria",
                        "alongamento de unhas",
                        "alongamento",
                        "banho de gel",
                        "unha em gel",
                        "unhas em gel",
                        "fibra de vidro",
                        "esmaltação",
                        "esmaltacao",
                        "nail design",
                        "design de unhas"
                );
        register(
                "nails",
                nailAliases,
                List.of(
                        rule(
                                exact(
                                        "shop",
                                        "beauty"
                                ),
                                token(
                                        "beauty",
                                        "nails",
                                        "manicure",
                                        "pedicure"
                                )
                        ),
                        rule(
                                exact(
                                        "shop",
                                        "hairdresser"
                                ),
                                token(
                                        "beauty",
                                        "nails",
                                        "manicure",
                                        "pedicure"
                                )
                        ),
                        rule(
                                exact(
                                        "shop",
                                        "nail_salon"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(
                                        exact(
                                                "shop",
                                                "beauty"
                                        )
                                ),
                                rule(
                                        exact(
                                                "shop",
                                                "hairdresser"
                                        )
                                ),
                                rule(
                                        exact(
                                                "shop",
                                                "nail_salon"
                                        )
                                )
                        ),
                        "nail",
                        "nails",
                        "nail designer",
                        "nail design",
                        "manicure",
                        "pedicure",
                        "esmalteria",
                        "unha",
                        "unhas",
                        "alongamento de unhas",
                        "alongamento",
                        "banho de gel",
                        "unha em gel",
                        "fibra de vidro",
                        "esmaltacao"
                )
        );

        // A.5 — Salão / cabelo
        register(
                "hairdresser",
                List.of(
                        "salao",
                        "salao de beleza",
                        "salao de cabelereiro",
                        "beleza salao",
                        "cabeleireiro",
                        "cabeleireira",
                        "cabelo",
                        "cabelos",
                        "cabelereiro",
                        "hair",
                        "hairdresser",
                        "hair salon",
                        "coiffeur"
                ),
                List.of(
                        rule(
                                exact(
                                        "shop",
                                        "hairdresser"
                                )
                        ),
                        rule(
                                exact(
                                        "shop",
                                        "beauty"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("shop", "hairdresser")),
                                rule(exact("shop", "beauty"))
                        ),
                        "salao",
                        "salao de beleza",
                        "cabeleireiro",
                        "cabeleireira",
                        "cabelo",
                        "hair",
                        "hairdresser",
                        "coiffeur"
                )
        );

        // A.6 — Barbearia
        List<String> barberAliases =
                List.of(
                        "barbearia",
                        "barbearias",
                        "barber",
                        "barbershop",
                        "barber shop",
                        "barba"
                );
        register(
                "barber",
                barberAliases,
                List.of(
                        rule(
                                exact(
                                        "shop",
                                        "hairdresser"
                                ),
                                token(
                                        "hairdresser",
                                        "barber"
                                )
                        ),
                        rule(
                                exact(
                                        "shop",
                                        "barber"
                                )
                        ),
                        rule(
                                exact(
                                        "shop",
                                        "hairdresser"
                                ),
                                exact(
                                        "barber",
                                        "yes"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(
                                        exact(
                                                "shop",
                                                "hairdresser"
                                        )
                                ),
                                rule(
                                        exact(
                                                "shop",
                                                "barber"
                                        )
                                )
                        ),
                        "barbearia",
                        "barbearias",
                        "barber",
                        "barbershop",
                        "barber shop"
                )
        );

        // A.7 — Dentista
        register(
                "dentist",
                List.of(
                        "dentista",
                        "dentistas",
                        "odontologia",
                        "clinica odontologica",
                        "consultorio odontologico",
                        "dentist",
                        "dental"
                ),
                List.of(
                        rule(
                                exact(
                                        "amenity",
                                        "dentist"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("amenity", "dentist"))
                        ),
                        "dentista",
                        "odontologia",
                        "dentist",
                        "dental"
                )
        );

        // A.8 — Clínica genérica
        register(
                "clinic",
                List.of(
                        "clinica",
                        "clinicas",
                        "clinica medica",
                        "consultorio",
                        "consultorio medico",
                        "medico",
                        "saude"
                ),
                List.of(
                        rule(
                                exact(
                                        "amenity",
                                        "clinic"
                                )
                        ),
                        rule(
                                exact(
                                        "amenity",
                                        "doctors"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("amenity", "clinic")),
                                rule(exact("amenity", "doctors"))
                        ),
                        "clinica",
                        "clinicas",
                        "consultorio",
                        "medico",
                        "saude"
                )
        );

        // A.9 — Massagem / spa
        register(
                "massage",
                List.of(
                        "massagem",
                        "massoterapia",
                        "massagista",
                        "spa",
                        "terapia",
                        "massage",
                        "fisioterapia",
                        "pilates"
                ),
                List.of(
                        rule(
                                exact(
                                        "shop",
                                        "beauty"
                                ),
                                token(
                                        "beauty",
                                        "massage"
                                )
                        ),
                        rule(
                                exact(
                                        "leisure",
                                        "spa"
                                )
                        ),
                        rule(
                                exact(
                                        "shop",
                                        "massage"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("shop", "beauty"), token("beauty", "massage")),
                                rule(exact("leisure", "spa")),
                                rule(exact("shop", "massage"))
                        ),
                        "massagem",
                        "massoterapia",
                        "spa",
                        "massage",
                        "pilates",
                        "fisioterapia"
                )
        );

        // A.10 — Depilação
        register(
                "hair_removal",
                List.of(
                        "depilacao",
                        "depiladora",
                        "epilacao",
                        "hair removal",
                        "laser",
                        "depilacao a laser"
                ),
                List.of(
                        rule(
                                exact(
                                        "shop",
                                        "beauty"
                                ),
                                token(
                                        "beauty",
                                        "hair_removal"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("shop", "beauty"))
                        ),
                        "depilacao",
                        "epilacao",
                        "hair removal",
                        "laser",
                        "depilacao a laser"
                )
        );

        // A.11 — Beauty genérico
        register(
                "beauty_generic",
                List.of(
                        "estudio",
                        "beauty",
                        "skincare",
                        "skin care",
                        "maquiagem",
                        "makeup"
                ),
                List.of(
                        rule(
                                exact(
                                        "shop",
                                        "beauty"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("shop", "beauty"))
                        ),
                        "estudio",
                        "beauty",
                        "skincare",
                        "makeup",
                        "maquiagem"
                )
        );

        // A.12 — Academia
        register(
                "fitness",
                List.of(
                        "academia",
                        "academias",
                        "fitness",
                        "crossfit",
                        "musculacao"
                ),
                List.of(
                        rule(
                                exact(
                                        "leisure",
                                        "fitness_centre"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("leisure", "fitness_centre"))
                        ),
                        "academia",
                        "fitness",
                        "crossfit",
                        "musculacao"
                )
        );

        // A.13 — Restaurante
        register(
                "restaurant",
                List.of(
                        "restaurante",
                        "restaurantes",
                        "comida",
                        "lanchonete"
                ),
                List.of(
                        rule(
                                exact(
                                        "amenity",
                                        "restaurant"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("amenity", "restaurant"))
                        ),
                        "restaurante",
                        "comida",
                        "lanchonete"
                )
        );

        // A.14 — Café
        register(
                "cafe",
                List.of(
                        "cafe",
                        "cafeteria"
                ),
                List.of(
                        rule(
                                exact(
                                        "amenity",
                                        "cafe"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("amenity", "cafe"))
                        ),
                        "cafe",
                        "cafeteria"
                )
        );

        // A.15 — Padaria
        register(
                "bakery",
                List.of(
                        "padaria",
                        "panificadora"
                ),
                List.of(
                        rule(
                                exact(
                                        "shop",
                                        "bakery"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("shop", "bakery"))
                        ),
                        "padaria",
                        "panificadora"
                )
        );

        // A.16 — Hotel
        register(
                "hotel",
                List.of(
                        "hotel",
                        "hoteis",
                        "pousada",
                        "hospedagem"
                ),
                List.of(
                        rule(
                                exact(
                                        "tourism",
                                        "hotel"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("tourism", "hotel"))
                        ),
                        "hotel",
                        "pousada",
                        "hospedagem"
                )
        );

        // A.17 — Pet shop
        register(
                "pet",
                List.of(
                        "pet",
                        "petshop",
                        "banho e tosa"
                ),
                List.of(
                        rule(
                                exact(
                                        "shop",
                                        "pet"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("shop", "pet"))
                        ),
                        "pet",
                        "petshop",
                        "banho e tosa"
                )
        );

        // A.18 — Veterinário
        register(
                "veterinary",
                List.of(
                        "veterinario",
                        "veterinaria",
                        "vet"
                ),
                List.of(
                        rule(
                                exact(
                                        "amenity",
                                        "veterinary"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("amenity", "veterinary"))
                        ),
                        "veterinario",
                        "vet"
                )
        );

        // A.19 — Farmácia
        register(
                "pharmacy",
                List.of(
                        "farmacia",
                        "drogaria"
                ),
                List.of(
                        rule(
                                exact(
                                        "amenity",
                                        "pharmacy"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("amenity", "pharmacy"))
                        ),
                        "farmacia",
                        "drogaria"
                )
        );

        // A.20 — Advogado
        register(
                "lawyer",
                List.of(
                        "advogado",
                        "advocacia",
                        "advogados"
                ),
                List.of(
                        rule(
                                exact(
                                        "office",
                                        "lawyer"
                                )
                        )
                ),
                fallback(
                        List.of(
                                rule(exact("office", "lawyer"))
                        ),
                        "advogado",
                        "advocacia"
                )
        );
    }

    public record NicheMatchDiagnostic(
            boolean matched,
            String matchType,
            String matchedRule,
            Map<String, String> relevantTags
    ) {}

    public static NicheMatchDiagnostic diagnoseMatch(
            NicheStrategy strategy,
            JsonNode tags,
            String normalizedName
    ) {
        if (strategy == null) {
            return new NicheMatchDiagnostic(false, "NO_STRATEGY", null, Map.of());
        }

        // Check structured rules
        if (strategy.structuredRules() != null) {
            for (NicheRule rule : strategy.structuredRules()) {
                boolean allMatch = true;
                StringBuilder ruleDesc = new StringBuilder();
                for (int i = 0; i < rule.allOf().size(); i++) {
                    TagCondition cond = rule.allOf().get(i);
                    if (i > 0) ruleDesc.append(" + ");
                    ruleDesc.append(cond.key()).append(cond.mode() == MatchMode.EXACT ? "=" : "~").append(String.join(",", cond.acceptedValues()));
                    if (!matchesCondition(tags, cond)) {
                        allMatch = false;
                    }
                }
                if (allMatch) {
                    Map<String, String> relevant = extractRelevantTags(tags);
                    return new NicheMatchDiagnostic(true, "STRUCTURED_RULE", ruleDesc.toString(), relevant);
                }
            }
        }

        // Check name fallback
        if (strategy.nameFallback() != null && strategy.nameFallback().enabled()) {
            boolean contextMatch = true;
            if (strategy.nameFallback().requiresContext()) {
                contextMatch = matchesAnyRule(tags, strategy.nameFallback().contextAnyOf());
            }
            if (contextMatch) {
                for (String alias : strategy.nameFallback().aliases()) {
                    if (containsNamePhrase(normalizedName, alias)) {
                        Map<String, String> relevant = extractRelevantTags(tags);
                        return new NicheMatchDiagnostic(true, "NAME_FALLBACK", alias, relevant);
                    }
                }
            }
        }

        Map<String, String> relevant = extractRelevantTags(tags);
        return new NicheMatchDiagnostic(false, "NO_MATCH", null, relevant);
    }

    private static Map<String, String> extractRelevantTags(JsonNode tags) {
        if (tags == null || !tags.isObject()) return Map.of();
        Map<String, String> relevant = new LinkedHashMap<>();
        String[] keys = {"shop", "beauty", "hairdresser", "barber", "craft", "amenity", "healthcare", "leisure", "office", "tourism"};
        for (String key : keys) {
            if (tags.has(key)) {
                relevant.put(key, tags.get(key).asText());
            }
        }
        return relevant;
    }

    private static boolean matchesCondition(JsonNode tags, TagCondition condition) {
        String actual = tag(tags, condition.key());

        if (actual == null || actual.isBlank()) {
            return false;
        }

        if (condition.mode() == MatchMode.EXACT) {
            return condition
                    .acceptedValues()
                    .stream()
                    .anyMatch(
                            expected ->
                                    actual.trim()
                                            .equalsIgnoreCase(
                                                    expected
                                            )
                    );
        } else if (condition.mode() == MatchMode.SEMICOLON_TOKEN) {
            java.util.Set<String> tokens =
                    java.util.Arrays
                            .stream(
                                    actual.split(";")
                            )
                            .map(String::trim)
                            .filter(v ->
                                    !v.isBlank()
                            )
                            .map(v ->
                                    v.toLowerCase(
                                            Locale.ROOT
                                    )
                            )
                            .collect(
                                    java.util.stream.Collectors
                                            .toSet()
                            );

            return condition
                    .acceptedValues()
                    .stream()
                    .anyMatch(tokens::contains);
        } else {
            return false;
        }
    }

    private static boolean matchesAnyRule(JsonNode tags, List<NicheRule> rules) {
        return rules != null
                && rules.stream()
                .anyMatch(
                        rule ->
                                matchesRule(tags, rule)
                );
    }

    private static boolean matchesRule(JsonNode tags, NicheRule rule) {
        return rule
                .allOf()
                .stream()
                .allMatch(
                        condition ->
                                matchesCondition(tags, condition)
                );
    }

    private static boolean containsNamePhrase(String normalizedName, String alias) {
        String name = normalizeNamePhrase(normalizedName);
        String normalizedAlias = normalizeNamePhrase(alias);

        if (name.isBlank() || normalizedAlias.isBlank()) {
            return false;
        }

        return (" " + name + " ").contains(" " + normalizedAlias + " ");
    }

    private static String tag(JsonNode tags, String key) {
        if (tags == null || key == null) {
            return null;
        }

        JsonNode value = tags.get(key);

        if (value == null || value.isNull()) {
            return null;
        }

        String text = value.asText();

        return text == null || text.isBlank()
                ? null
                : text.trim();
    }

    /** Resolve a estrategia de busca para um nicho livre. Nunca retorna null. */
    public static NicheStrategy resolve(
            String niche
    ) {
        String key = normalizeKey(niche);

        NicheStrategy exact =
                ALIAS_TO_STRATEGY.get(key);

        if (exact != null) {
            return exact;
        }

        List<Map.Entry<
                String,
                NicheStrategy
                >> entries =
                new ArrayList<>(
                        ALIAS_TO_STRATEGY
                                .entrySet()
                );

        entries.sort(
                (a, b) ->
                        Integer.compare(
                                b.getKey().length(),
                                a.getKey().length()
                        )
        );

        for (
                Map.Entry<
                        String,
                        NicheStrategy
                        > entry : entries
        ) {
            if (
                    !entry.getKey().isBlank()
                            && key.contains(
                            entry.getKey()
                    )
            ) {
                return entry.getValue();
            }
        }

        String unknownAlias =
                normalizeNamePhrase(niche);

        return new NicheStrategy(
                unknownAlias,
                List.of(),
                new NameFallback(
                        List.of(),
                        unknownAlias.isBlank()
                                ? List.of()
                                : List.of(
                                unknownAlias
                        )
                )
        );
    }

    // ------------------------------------------------------------------
    // Two-stage classification: POTENTIAL (amplo, antes do enrichment)
    // vs CONFIRMED (nicho confirmado, apos enrichment oficial).
    // shop=beauty sozinho autoriza investigacao, nunca confirma Nails.
    // ------------------------------------------------------------------

    public record NicheConfirmation(boolean confirmed, String evidenceType, String evidenceDetails) {}

    private static final List<String> BROAD_COMMERCIAL_KEYS = List.of(
            "shop", "amenity", "craft", "healthcare", "leisure", "office", "tourism");

    public static boolean isPotential(NicheStrategy strategy, JsonNode tags, String normalizedName) {
        if (strategy == null) return false;
        // Regra forte ja qualifica como potencial.
        if (matchesAnyRule(tags, strategy.structuredRules())) return true;
        // Contexto do fallback (ex: shop=beauty, shop=hairdresser) autoriza investigacao.
        if (strategy.nameFallback() != null
                && strategy.nameFallback().requiresContext()
                && matchesAnyRule(tags, strategy.nameFallback().contextAnyOf())) {
            return true;
        }
        // Qualquer objeto comercial com nome contendo alias tambem e potencial.
        if (hasAnyCommercialTag(tags) && containsAnyAlias(normalizedName, strategy)) {
            return true;
        }
        // Nome com alias mesmo sem tag comercial conhecida: ainda investiga
        // (enrichment oficial decide; evita rejeicao precoce).
        if (containsAnyAlias(normalizedName, strategy)) {
            return true;
        }
        // Objeto comercial generico sem evidencia: potencial para nao matar cedo,
        // a confirmacao rigorosa acontece depois do enrichment.
        return hasAnyCommercialTag(tags);
    }

    public static NicheConfirmation confirm(NicheStrategy strategy, JsonNode tags, String officialText) {
        if (strategy == null) {
            return new NicheConfirmation(false, "NONE", "no strategy");
        }
        // 1. Tag OSM forte confirma sozinha.
        if (matchesAnyRule(tags, strategy.structuredRules())) {
            return new NicheConfirmation(true, "TAG_STRONG", describeRules(strategy.structuredRules()));
        }
        // 2. Texto oficial (nome + descricao + servicos + website oficial + hub ligado pelo OSM).
        String haystack = normalizeNamePhrase(
                (tagsNameText(tags) == null ? "" : tagsNameText(tags) + " ")
                        + (officialText == null ? "" : officialText));
        String padded = " " + haystack + " ";
        if (strategy.nameFallback() != null) {
            for (String alias : strategy.nameFallback().aliases()) {
                if (alias != null && !alias.isBlank() && padded.contains(" " + alias + " ")) {
                    // Se fallback exige contexto, o contexto precisa existir nas tags
                    // ou o texto oficial precisa trazer evidencia (nome/site ja conta).
                    return new NicheConfirmation(true, "OFFICIAL_TEXT", alias);
                }
            }
        }
        return new NicheConfirmation(false, "NONE", "niche not confirmed in tags or official text");
    }

    private static boolean hasAnyCommercialTag(JsonNode tags) {
        if (tags == null || !tags.isObject()) return false;
        for (String key : BROAD_COMMERCIAL_KEYS) {
            JsonNode v = tags.get(key);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return true;
        }
        // Objeto com contato oficial tambem merece investigacao.
        for (String key : new String[]{"website", "contact:website", "url", "phone", "contact:phone",
                "contact:instagram", "instagram"}) {
            JsonNode v = tags.get(key);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return true;
        }
        return false;
    }

    private static boolean containsAnyAlias(String normalizedName, NicheStrategy strategy) {
        if (strategy.nameFallback() == null || normalizedName == null) return false;
        String name = normalizeNamePhrase(normalizedName);
        if (name.isBlank()) return false;
        String padded = " " + name + " ";
        for (String alias : strategy.nameFallback().aliases()) {
            if (alias != null && !alias.isBlank() && padded.contains(" " + alias + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String tagsNameText(JsonNode tags) {
        if (tags == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String key : new String[]{"name", "description", "service", "services", "beauty", "shop", "craft"}) {
            JsonNode v = tags.get(key);
            if (v != null && !v.isNull() && !v.asText().isBlank()) {
                sb.append(v.asText()).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private static String describeRules(List<NicheRule> rules) {
        if (rules == null || rules.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (NicheRule rule : rules) {
            if (sb.length() > 0) sb.append(" OR ");
            sb.append(rule.allOf().stream()
                    .map(c -> c.key() + "=" + String.join("|", c.acceptedValues()))
                    .reduce((a, b) -> a + "+" + b).orElse(""));
        }
        String s = sb.toString();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
