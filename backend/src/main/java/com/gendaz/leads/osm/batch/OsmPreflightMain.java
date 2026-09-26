package com.gendaz.leads.osm.batch;

import com.gendaz.leads.osm.whatsapp.WhatsAppPreflightService;

/**
 * CLI do preflight WhatsApp (substitui whatsapp_sync_preflight.py).
 * Uso: OsmPreflightMain (lê OSM_SYNC_WHATSAPP_SERVICE_URL e
 * OSM_SYNC_WHATSAPP_INTERNAL_TOKEN do ambiente).
 */
public class OsmPreflightMain {

    public static void main(String[] args) {
        String url = firstNonBlank(
                System.getenv("OSM_SYNC_WHATSAPP_SERVICE_URL"),
                System.getenv("WHATSAPP_SERVICE_URL"));
        String token = firstNonBlank(
                System.getenv("OSM_SYNC_WHATSAPP_INTERNAL_TOKEN"),
                System.getenv("WHATSAPP_INTERNAL_TOKEN"));
        if (url == null || token == null) {
            System.err.println("[osm-preflight] missing OSM_SYNC_WHATSAPP_SERVICE_URL / INTERNAL_TOKEN");
            System.exit(2);
        }
        new WhatsAppPreflightService(url, token).ensureReady();
        System.out.println("[osm-preflight] whatsapp ready");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
