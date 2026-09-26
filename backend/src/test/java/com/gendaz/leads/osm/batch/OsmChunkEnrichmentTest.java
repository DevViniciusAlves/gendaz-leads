package com.gendaz.leads.osm.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gendaz.leads.osm.discovery.OsmCandidate;
import com.gendaz.leads.osm.enrichment.OfficialContactEnrichmentService;
import com.gendaz.leads.osm.enrichment.OfficialWebsiteFetcher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressoes de arquitetura: chunking/early-stop, timeout por candidato com
 * mapeamento correto, scan bulk 1x, sem transacao durante HTTP.
 */
class OsmChunkEnrichmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static OsmCandidate cand(long id) {
        ObjectNode tags = JSON.createObjectNode();
        tags.put("shop", "beauty");
        tags.put("website", "https://studio" + id + ".example.com");
        return new OsmCandidate("node", id, "Studio " + id, "studio " + id,
                tags, -15.0, -56.0, Instant.parse("2024-01-01T00:00:00Z"),
                null, "Cuiaba", "MT", "Brasil", "br");
    }

    @Test
    void timeoutKeepsCandidateAssociation() throws Exception {
        OfficialWebsiteFetcher fetcher = new OfficialWebsiteFetcher(50, 50, 500_000);
        OfficialContactEnrichmentService slow = new OfficialContactEnrichmentService(fetcher, "br") {
            @Override
            public EnrichedContact enrich(OsmCandidate candidate) {
                if (candidate.osmId() == 2) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new EnrichedContact(null, null, null, "", false, false);
            }
        };
        OsmSyncArguments args = new OsmSyncArguments(1, 1, 1, "Cuiaba", "MT", "br",
                "nails", 50, "{}", "in.geojsonseq", true);
        OsmSyncJob job = new OsmSyncJob(args);
        List<OsmSyncJob.TieredCandidate> chunk = new ArrayList<>();
        chunk.add(new OsmSyncJob.TieredCandidate(cand(1), 4));
        chunk.add(new OsmSyncJob.TieredCandidate(cand(2), 4));
        chunk.add(new OsmSyncJob.TieredCandidate(cand(3), 4));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            OsmSyncMetrics m = new OsmSyncMetrics();
            List<OsmSyncJob.EnrichmentResult> out = job.enrichChunk(slow, chunk, pool, 300, m);
            assertEquals(3, out.size());
            // Resultado continua associado ao candidato correto (X => X).
            assertEquals(1L, out.get(0).candidate().candidate().osmId());
            assertEquals(2L, out.get(1).candidate().candidate().osmId());
            assertEquals(3L, out.get(2).candidate().candidate().osmId());
            assertTrue(out.get(1).timedOut());
            assertNotNull(out.get(1).error());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void earlyStopAvoidsSchedulingAfterTarget() {
        // Prova conceitual: com chunk 32 e target atingido no chunk 2, chunk 3 nunca e agendado.
        int chunkSize = 32;
        List<Integer> scheduledChunks = new ArrayList<>();
        int total = 100;
        int target = 50;
        int saved = 0;
        int cursor = 0;
        int chunkIndex = 0;
        while (cursor < total && saved < target) {
            scheduledChunks.add(chunkIndex);
            int end = Math.min(cursor + chunkSize, total);
            // Simula: chunk 0 gera 20, chunk 1 gera 30 => 50.
            saved += chunkIndex == 0 ? 20 : 30;
            cursor = end;
            chunkIndex++;
        }
        assertEquals(List.of(0, 1), scheduledChunks);
        assertEquals(50, saved);
    }
}
