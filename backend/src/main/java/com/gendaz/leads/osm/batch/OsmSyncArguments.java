package com.gendaz.leads.osm.batch;

import java.util.HashMap;
import java.util.Map;

/**
 * Argumentos do batch Java. O workflow continua responsavel por osmium/PBF;
 * o Java decide tudo sobre lead (nunca YAML/shell).
 */
public record OsmSyncArguments(
        long syncRunId,
        long regionId,
        long targetId,
        String city,
        String state,
        String countryCode,
        String canonicalNiche,
        int targetValid,
        String nicheStrategyJson,
        String input,
        boolean dryRun
) {
    public static OsmSyncArguments parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(a.substring(2).replace('-', '_'), args[i + 1]);
                i++;
            } else if (a.startsWith("--")) {
                m.put(a.substring(2).replace('-', '_'), "true");
            }
        }
        // Aliases vindos do workflow Python legado.
        long syncRunId = parseLong(first(m, "sync_run_id", "sync_run"), "sync-run-id");
        long regionId = parseLong(first(m, "region_id", "region"), "region-id");
        long targetId = parseLongOpt(first(m, "target_id", "target"));
        String city = first(m, "city", null);
        String state = first(m, "state", null);
        String countryCode = first(m, "country_code", "country");
        String canonical = first(m, "canonical_niche", "canonical");
        int targetValid = (int) parseLongOptDefault(first(m, "target_valid", "target"), 50L);
        String strategyJson = first(m, "niche_strategy_json", "strategy");
        String input = first(m, "input", null);
        boolean dryRun = Boolean.parseBoolean(m.getOrDefault("dry_run", "false"));
        if (city == null || canonical == null || input == null) {
            throw new IllegalArgumentException(
                    "Uso: OsmSyncBatchMain --sync-run-id <id> --region-id <id> [--target-id <id>] "
                            + "--city <cidade> --state <estado> --country-code br "
                            + "--canonical-niche <nicho> --target-valid 50 "
                            + "--niche-strategy-json <json> --input <geojsonseq>");
        }
        return new OsmSyncArguments(syncRunId, regionId, targetId, city, state,
                countryCode == null ? "br" : countryCode, canonical, targetValid,
                strategyJson == null ? "{}" : strategyJson, input, dryRun);
    }

    private static String first(Map<String, String> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k)) return m.get(k);
        }
        return null;
    }

    private static long parseLong(String v, String name) {
        if (v == null) throw new IllegalArgumentException("Missing --" + name);
        return Long.parseLong(v);
    }

    private static long parseLongOpt(String v) {
        if (v == null) return 0L;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long parseLongOptDefault(String v, long def) {
        if (v == null) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
