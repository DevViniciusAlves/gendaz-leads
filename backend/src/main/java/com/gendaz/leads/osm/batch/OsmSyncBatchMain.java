package com.gendaz.leads.osm.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry-point do batch OSM em Java. O workflow GitHub continua responsavel
 * por Geofabrik/osmium (download, checksum, boundary, extract, filter,
 * export); o Java le o GeoJSONSeq, classifica, enriquece, valida WhatsApp,
 * deduplica e publica — 100% da regra de negocio em Java.
 */
public class OsmSyncBatchMain {

    private static final Logger log = LoggerFactory.getLogger(OsmSyncBatchMain.class);

    public static void main(String[] args) throws Exception {
        OsmSyncArguments parsed = OsmSyncArguments.parse(args);
        log.info("[osm-sync] batch_start runId={} targetId={} regionId={} city={} canonical={} targetValid={} input={}",
                parsed.syncRunId(), parsed.targetId(), parsed.regionId(), parsed.city(),
                parsed.canonicalNiche(), parsed.targetValid(), parsed.input());
        OsmSyncMetrics metrics = new OsmSyncJob(parsed).run();
        log.info("[osm-sync] batch_done runId={} qualifiedSaved={} statusExhausted={}",
                parsed.syncRunId(), metrics.qualifiedSaved, metrics.datasetExhausted);
    }
}
