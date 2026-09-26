package com.gendaz.leads.osm.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.osm.discovery.OsmCandidate;
import com.gendaz.leads.osm.discovery.OsmGeoJsonSeqReader;
import com.gendaz.leads.osm.discovery.OsmSourceTimestamp;
import com.gendaz.leads.osm.enrichment.OfficialContactEnrichmentService;
import com.gendaz.leads.osm.enrichment.OfficialWebsiteFetcher;
import com.gendaz.leads.osm.persistence.BatchDataSource;
import com.gendaz.leads.osm.persistence.OsmCandidateScanStateRepository;
import com.gendaz.leads.osm.persistence.OsmDeduplicator;
import com.gendaz.leads.osm.persistence.OsmQualifiedPoolRepository;
import com.gendaz.leads.osm.whatsapp.WhatsAppInfrastructureException;
import com.gendaz.leads.osm.whatsapp.WhatsAppPreflightService;
import com.gendaz.leads.osm.whatsapp.WhatsAppRecipientCheckResult;
import com.gendaz.leads.osm.whatsapp.WhatsAppRecipientValidationClient;
import com.gendaz.leads.service.provider.NicheMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Motor target-50 em Java:
 * qualifiedSaved == 50 => SUCCESS; 1..49 com dataset esgotado => PARTIAL;
 * 0 com dataset esgotado => EXHAUSTED; erro tecnico real => FAILED.
 * Candidatos tecnicamente inconclusivos (timeout/infra) => PARTIAL com
 * datasetExhausted=false, nunca EXHAUSTED.
 * Enrichment oficial acontece ANTES da rejeicao definitiva do nicho.
 * Instagram + telefone + WhatsApp obrigatorios. Dedup antes do WPP e antes de contar.
 *
 * Performance (java-osm-v2):
 * - scan state carregado 1x (bulk) + lookup in-memory, zero conexao por candidato
 * - potential antes de qualquer DB; !potential nao gera write (so metrica)
 * - scan rejections em batch com conexao curta por lote (sem tx longa)
 * - enrichment paralelo bounded por chunks com early stop (sem HTTP apos 50)
 * - timeout por candidato; future carrega candidato explicito (sem out.size())
 * - nenhuma transacao DB aberta durante HTTP
 */
public class OsmSyncJob {

    private static final Logger log = LoggerFactory.getLogger(OsmSyncJob.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SCAN_BATCH_SIZE = 200;

    private final OsmSyncArguments args;
    private final NicheMapper.NicheStrategy strategy;

    public OsmSyncJob(OsmSyncArguments args) {
        this.args = args;
        this.strategy = NicheMapper.resolve(args.canonicalNiche());
    }

    record PendingScan(String osmType, long osmId, Instant sourceTimestamp, String outcome,
                       String phone, String ig, String details) {}

    record TieredCandidate(OsmCandidate candidate, int tier) {}

    record EnrichedItem(TieredCandidate tiered,
                        OfficialContactEnrichmentService.EnrichedContact enriched) {}

    record EnrichmentResult(TieredCandidate candidate,
                            OfficialContactEnrichmentService.EnrichedContact contact,
                            Throwable error,
                            boolean timedOut) {}

    public OsmSyncMetrics run() throws Exception {
        OsmSyncMetrics m = new OsmSyncMetrics();
        long jobStart = System.currentTimeMillis();
        String wppUrl = env("OSM_SYNC_WHATSAPP_SERVICE_URL", "WHATSAPP_SERVICE_URL");
        String wppToken = env("OSM_SYNC_WHATSAPP_INTERNAL_TOKEN", "WHATSAPP_INTERNAL_TOKEN");
        int workers = envInt("OSM_SYNC_WEBSITE_MAX_WORKERS", 8);
        int connectTimeout = envInt("OSM_SYNC_CONNECT_TIMEOUT_MS", 2000);
        int readTimeout = envInt("OSM_SYNC_READ_TIMEOUT_MS", 3500);
        int enrichChunkSize = envInt("OSM_SYNC_ENRICH_BATCH_SIZE", 32);
        long candidateTimeoutMs = envLong("OSM_SYNC_CANDIDATE_ENRICH_TIMEOUT_MS", 12_000L);
        if (workers < 1) workers = 1;
        if (workers > 16) workers = 16;
        if (enrichChunkSize < 1) enrichChunkSize = 32;
        if (enrichChunkSize > 100) enrichChunkSize = 100;
        final int workersFinal = workers;

        // Conexao A curta: contexto + RUNNING + snapshots, depois commit/close.
        RunContext ctx;
        OsmDeduplicator dedup = new OsmDeduplicator();
        Map<String, OsmCandidateScanStateRepository.ScanEntry> scanStates;
        OsmCandidateScanStateRepository scanRepo = new OsmCandidateScanStateRepository();
        try (Connection con = BatchDataSource.open()) {
            con.setAutoCommit(false);
            ctx = loadRunContext(con);
            markRunning(con, ctx);
            con.commit();
        }
        // Preflight fora de qualquer transacao DB.
        if (!args.dryRun()) {
            new WhatsAppPreflightService(wppUrl, wppToken).ensureReady();
            logStage("PRECHECK", "whatsapp preflight ok");
        }
        // Snapshots em conexao curta separada (sem tx longa).
        try (Connection con = BatchDataSource.open()) {
            con.setAutoCommit(true);
            dedup.loadPool(con, args.regionId(), ctx.targetId());
            long t0 = System.currentTimeMillis();
            scanStates = scanRepo.loadAll(con, args.regionId(), ctx.targetId(),
                    ctx.canonicalNiche(), OsmCandidateScanStateRepository.PIPELINE_VERSION);
            m.stageLoadScanStateMs = System.currentTimeMillis() - t0;
            logStage("LOAD_SCAN_STATE", "loaded=" + scanStates.size()
                    + " durationMs=" + m.stageLoadScanStateMs);
        }

        try {
            OfficialWebsiteFetcher fetcher = new OfficialWebsiteFetcher(connectTimeout, readTimeout, 500_000);
            OfficialContactEnrichmentService enrichment =
                    new OfficialContactEnrichmentService(fetcher, args.countryCode());
            WhatsAppRecipientValidationClient wpp =
                    new WhatsAppRecipientValidationClient(wppUrl, wppToken, 3, 350);

            List<OsmQualifiedPoolRepository.QualifiedLead> staged = new ArrayList<>();
            List<PendingScan> pendingScans = new ArrayList<>();

            // Stage 2: read + commercial + potential + scan-skip in-memory + priority (CPU local, sem tx).
            long t0 = System.currentTimeMillis();
            List<TieredCandidate> tiered = new ArrayList<>();
            try (OsmGeoJsonSeqReader reader = new OsmGeoJsonSeqReader(
                    Path.of(args.input()), args.city(), args.state(), null, args.countryCode())) {
                for (OsmCandidate candidate : reader) {
                    if (candidate == null) continue;
                    m.objectsRead++;
                    m.candidatesScanned++;
                    if (candidate.sourceTimestamp() == null) m.invalidSourceTimestamp++;

                    if (!isCommercialOrContact(candidate)) continue;
                    m.commercialCandidates++;

                    // !potential: barato/local, sem DB write (evita milhoes de linhas).
                    if (!NicheMapper.isPotential(strategy, candidate.tags(), candidate.normalizedName())) {
                        m.discardedNiche++;
                        continue;
                    }
                    m.potentialNicheCandidates++;

                    OsmCandidateScanStateRepository.ScanEntry entry =
                            scanStates.get(candidate.stableKey());
                    if (entry != null && scanRepo.shouldSkip(entry, candidate.sourceTimestamp())) {
                        m.scanStateSkipped++;
                        continue;
                    }

                    // Fast reject: sem canal oficial -> zero HTTP.
                    if (!OfficialContactEnrichmentService.hasOfficialContactChannel(candidate.tags())) {
                        m.fastRejectedNoOfficialChannel++;
                        m.discardedNoOfficialChannel++;
                        pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                                candidate.sourceTimestamp(), "NO_OFFICIAL_CONTACT_CHANNEL", null, null,
                                "sem canal oficial"));
                        flushScanBatch(ctx, scanRepo, pendingScans, false, m);
                        continue;
                    }

                    tiered.add(new TieredCandidate(candidate, priorityTier(candidate)));
                }
            }
            tiered.sort(Comparator.comparingInt(TieredCandidate::tier));
            m.stageReadClassifyMs = System.currentTimeMillis() - t0;
            logStage("READ_CLASSIFY", "tiered=" + tiered.size()
                    + " durationMs=" + m.stageReadClassifyMs);

            // Stage 3+4: chunks ordenados com early stop real (nao agenda HTTP apos 50).
            t0 = System.currentTimeMillis();
            ExecutorService pool = Executors.newFixedThreadPool(workersFinal);
            long technicalInconclusive = 0;
            try {
                int cursor = 0;
                while (cursor < tiered.size() && m.qualifiedSaved < args.targetValid()) {
                    int end = Math.min(cursor + enrichChunkSize, tiered.size());
                    List<TieredCandidate> chunk = tiered.subList(cursor, end);
                    cursor = end;
                    m.enrichmentChunks++;
                    List<EnrichmentResult> results =
                            enrichChunk(enrichment, chunk, pool, candidateTimeoutMs, m);
                    for (EnrichmentResult r : results) {
                        if (m.qualifiedSaved >= args.targetValid()) break;
                        if (r.error() != null || r.contact() == null) {
                            m.technicalEnrichmentFailures++;
                            if (r.timedOut()) m.enrichmentTimedOut++;
                            technicalInconclusive++;
                            continue;
                        }
                        OsmCandidate candidate = r.candidate().candidate();
                        OfficialContactEnrichmentService.EnrichedContact enriched = r.contact();

                        if (enriched.directPhone()) m.directPhone++;
                        else if (enriched.phone() != null) m.recoveredPhone++;
                        if (enriched.directInstagram()) m.directInstagram++;
                        else if (enriched.instagram() != null) m.recoveredInstagram++;

                        NicheMapper.NicheConfirmation confirmation = NicheMapper.confirm(
                                strategy, candidate.tags(), enriched.officialText());
                        if (!confirmation.confirmed()) {
                            m.discardedNiche++;
                            // Persiste NICHE_NOT_CONFIRMED somente pos-enrichment (era potential).
                            pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                                    candidate.sourceTimestamp(), "NICHE_NOT_CONFIRMED", null, null,
                                    "confirm failed"));
                            flushScanBatch(ctx, scanRepo, pendingScans, false, m);
                            continue;
                        }
                        m.nicheConfirmed++;

                        if (enriched.instagram() == null) {
                            m.discardedNoInstagram++;
                            pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                                    candidate.sourceTimestamp(), "NO_INSTAGRAM",
                                    enriched.phone() == null ? null : enriched.phone().normalized(),
                                    null, "sem instagram oficial"));
                            flushScanBatch(ctx, scanRepo, pendingScans, false, m);
                            continue;
                        }
                        if (enriched.phone() == null) {
                            m.discardedNoPhone++;
                            pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                                    candidate.sourceTimestamp(), "NO_PHONE", null,
                                    enriched.instagram().normalized(), "sem telefone oficial"));
                            flushScanBatch(ctx, scanRepo, pendingScans, false, m);
                            continue;
                        }

                        // Dedup ANTES do WPP.
                        OsmDeduplicator.DuplicateResult dup = dedup.check(candidate.osmType(), candidate.osmId(),
                                enriched.phone().normalized(), enriched.instagram().normalized());
                        if (dup.duplicate()) {
                            switch (dup.kind()) {
                                case SOURCE -> m.discardedDuplicateSource++;
                                case PHONE, GLOBAL -> m.discardedDuplicatePhone++;
                                case INSTAGRAM -> m.discardedDuplicateInstagram++;
                                default -> m.discardedDuplicateSource++;
                            }
                            pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                                    candidate.sourceTimestamp(), "DUPLICATE_PHONE",
                                    enriched.phone().normalized(), enriched.instagram().normalized(),
                                    "duplicate " + dup.kind()));
                            flushScanBatch(ctx, scanRepo, pendingScans, false, m);
                            continue;
                        }

                        m.whatsappChecks++;
                        WhatsAppRecipientCheckResult wppRes;
                        if (args.dryRun()) {
                            wppRes = WhatsAppRecipientCheckResult.found();
                        } else {
                            wppRes = wpp.check(enriched.phone().normalized());
                        }
                        if (wppRes.technicalFailure()) {
                            throw new WhatsAppInfrastructureException(wppRes.code(),
                                    "Falha tecnica WhatsApp durante validacao (" + wppRes.code() + ")");
                        }
                        if (!wppRes.exists()) {
                            m.discardedNotOnWhatsApp++;
                            pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                                    candidate.sourceTimestamp(), "NOT_ON_WHATSAPP",
                                    enriched.phone().normalized(), enriched.instagram().normalized(),
                                    "nao existe no WhatsApp"));
                            flushScanBatch(ctx, scanRepo, pendingScans, false, m);
                            continue;
                        }
                        m.whatsappVerified++;

                        OsmQualifiedPoolRepository.QualifiedLead lead =
                                toQualifiedLead(candidate, enriched, confirmation);
                        staged.add(lead);
                        dedup.markSeen(candidate.osmType(), candidate.osmId(),
                                enriched.phone().normalized(), enriched.instagram().normalized());
                        m.qualifiedSaved++;
                        log.info("[osm-sync] sync_summary runId={} targetId={} qualifiedSaved={}/{} candidate={} phoneSrc={} igSrc={}",
                                args.syncRunId(), args.targetId(), m.qualifiedSaved, args.targetValid(),
                                candidate.stableKey(),
                                enriched.phone().source(), enriched.instagram().source());
                    }
                }
                if (cursor < tiered.size() && m.qualifiedSaved >= args.targetValid()) {
                    m.candidatesNotScheduledAfterTargetReached = tiered.size() - cursor;
                }
            } finally {
                pool.shutdown();
                try {
                    pool.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            m.stageEnrichMs = System.currentTimeMillis() - t0;
            logStage("ENRICH", "qualified=" + m.qualifiedSaved
                    + " workers=" + workersFinal + " chunks=" + m.enrichmentChunks
                    + " durationMs=" + m.stageEnrichMs);

            // Metricas do fetcher/enrichment.
            // (enrichment instance guardado acima; recriar contadores aqui seria zero — usar refs locais.)
            // Nota: fetcher/enrichment sao locais deste bloco; copiar antes do publish.
            m.websiteCandidates = enrichmentRef(enrichment).websiteCandidates.get();
            m.websiteFetchSuccess = fetcherRef(fetcher).fetchSuccess.get();
            m.websiteFetchFailed = fetcherRef(fetcher).fetchFailed.get();
            m.websiteTimeout = fetcherRef(fetcher).fetchTimeout.get();
            m.website403 = fetcherRef(fetcher).fetch403.get();
            m.website404 = fetcherRef(fetcher).fetch404.get();
            m.website429 = fetcherRef(fetcher).fetch429.get();
            m.contactPagesFetched = enrichmentRef(enrichment).contactPagesFetched.get();
            m.contactHubCandidates = enrichmentRef(enrichment).contactHubCandidates.get();
            m.contactHubSuccess = enrichmentRef(enrichment).contactHubSuccess.get();
            m.sameAsLinksFound = enrichmentRef(enrichment).sameAsLinksFound.get();
            m.socialCandidates = enrichmentRef(enrichment).socialCandidates.get();
            m.socialSuccess = enrichmentRef(enrichment).socialSuccess.get();

            // Status do dataset: inconclusivos tecnicos nunca viram EXHAUSTED.
            String finalStatus;
            boolean targetReached = m.qualifiedSaved >= args.targetValid();
            if (targetReached) {
                finalStatus = "SUCCESS";
                m.datasetExhausted = false;
            } else if (technicalInconclusive > 0) {
                finalStatus = m.qualifiedSaved > 0 ? "PARTIAL" : "PARTIAL";
                m.datasetExhausted = false;
            } else if (m.qualifiedSaved > 0) {
                finalStatus = "PARTIAL";
                m.datasetExhausted = true;
            } else {
                finalStatus = "EXHAUSTED";
                m.datasetExhausted = true;
            }

            // Flush restante (conexao curta) antes da publicacao.
            t0 = System.currentTimeMillis();
            flushScanBatch(ctx, scanRepo, pendingScans, true, m);

            // Publicacao atomica propria (sem tx aberta durante HTTP).
            OsmQualifiedPoolRepository poolRepo = new OsmQualifiedPoolRepository();
            try (Connection con2 = BatchDataSource.open()) {
                con2.setAutoCommit(false);
                try {
                    for (OsmQualifiedPoolRepository.QualifiedLead lead : staged) {
                        long placeId = poolRepo.upsertPlace(con2, args.regionId(), lead);
                        if (ctx.targetId() > 0) {
                            poolRepo.upsertPlaceNiche(con2, placeId, ctx.targetId(), ctx.canonicalNiche(),
                                    lead.evidenceType(), lead.evidenceDetails());
                        }
                        scanRepo.upsert(con2, args.regionId(),
                                ctx.targetId() > 0 ? ctx.targetId() : null,
                                ctx.canonicalNiche(), lead.osmType(), lead.osmId(),
                                lead.sourceTimestamp(), "QUALIFIED",
                                lead.normalizedPhone(), lead.normalizedInstagram(), "qualified");
                    }
                    if (ctx.targetId() > 0) {
                        poolRepo.recalcTargetCounts(con2, ctx.targetId());
                    }
                    finalizeRun(con2, ctx, finalStatus, m, null);
                    updateTargetOutcome(con2, ctx, finalStatus, m, null);
                    con2.commit();
                } catch (Exception e) {
                    con2.rollback();
                    throw e;
                }
            }
            m.stageWppPublishMs = System.currentTimeMillis() - t0;
            m.durationMs = System.currentTimeMillis() - jobStart;
            logSummary(finalStatus, m);
            return m;
        } catch (WhatsAppInfrastructureException e) {
            try (Connection con = BatchDataSource.open()) {
                RunContext rc = loadRunContext(con);
                con.setAutoCommit(false);
                try {
                    finalizeRun(con, rc, "FAILED", m, e.getCode() + ": " + e.getMessage());
                    updateTargetOutcome(con, rc, "FAILED", m, e.getCode() + ": " + e.getMessage());
                    con.commit();
                } catch (Exception ex) {
                    con.rollback();
                }
            }
            throw e;
        } catch (Exception e) {
            try (Connection con = BatchDataSource.open()) {
                try {
                    RunContext rc = loadRunContext(con);
                    con.setAutoCommit(false);
                    try {
                        finalizeRun(con, rc, "FAILED", m, safe(e.getMessage()));
                        updateTargetOutcome(con, rc, "FAILED", m, safe(e.getMessage()));
                        con.commit();
                    } catch (Exception ex) {
                        con.rollback();
                    }
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    private static OfficialContactEnrichmentService enrichmentRef(OfficialContactEnrichmentService e) {
        return e;
    }

    private static OfficialWebsiteFetcher fetcherRef(OfficialWebsiteFetcher f) {
        return f;
    }

    /** Enrichment por chunk: timeout individual, candidato explicito no resultado. */
    List<EnrichmentResult> enrichChunk(OfficialContactEnrichmentService enrichment,
                                       List<TieredCandidate> chunk, ExecutorService pool,
                                       long candidateTimeoutMs, OsmSyncMetrics m) {
        List<CompletableFuture<EnrichmentResult>> futures = new ArrayList<>(chunk.size());
        for (TieredCandidate t : chunk) {
            CompletableFuture<EnrichmentResult> f = CompletableFuture.supplyAsync(() -> {
                try {
                    OfficialContactEnrichmentService.EnrichedContact e = enrichment.enrich(t.candidate());
                    return new EnrichmentResult(t, e, null, false);
                } catch (Throwable th) {
                    return new EnrichmentResult(t, null, th, false);
                }
            }, pool);
            // Timeout por candidato: nao derruba run, nao cacheia negativo definitivo.
            CompletableFuture<EnrichmentResult> withTimeout = f.orTimeout(candidateTimeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        Throwable cause = ex instanceof java.util.concurrent.TimeoutException ? ex
                                : ex.getCause() != null ? ex.getCause() : ex;
                        boolean timedOut = cause instanceof java.util.concurrent.TimeoutException
                                || (ex.toString() != null && ex.toString().contains("TimeoutException"));
                        return new EnrichmentResult(t, null, cause, timedOut);
                    });
            futures.add(withTimeout);
        }
        List<EnrichmentResult> out = new ArrayList<>(chunk.size());
        for (CompletableFuture<EnrichmentResult> f : futures) {
            try {
                out.add(f.join());
            } catch (Exception e) {
                // Jamais mapear por out.size(): fallback preserva ordem do chunk via indice.
                int idx = out.size();
                TieredCandidate t = idx < chunk.size() ? chunk.get(idx) : null;
                if (t != null) out.add(new EnrichmentResult(t, null, e, false));
            }
        }
        return out;
    }

    /** Scan batch com conexao curta propria (commit/close por lote). */
    void flushScanBatch(RunContext ctx, OsmCandidateScanStateRepository repo,
                        List<PendingScan> pending, boolean force, OsmSyncMetrics m) throws Exception {
        if (pending.isEmpty()) return;
        if (!force && pending.size() < SCAN_BATCH_SIZE) return;
        List<PendingScan> batch = new ArrayList<>(pending);
        pending.clear();
        String sql = "INSERT INTO osm_candidate_scan_state "
                + "(region_id, target_id, canonical_niche, osm_type, osm_id, source_timestamp, "
                + "outcome, normalized_phone, normalized_instagram, last_checked_at, retry_after, details, pipeline_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), "
                + "CASE WHEN ? IN ('QUALIFIED') THEN NULL ELSE NOW() + INTERVAL '30 days' END, ?, ?) "
                + "ON CONFLICT (region_id, canonical_niche, osm_type, osm_id) DO UPDATE SET "
                + "target_id = EXCLUDED.target_id, source_timestamp = EXCLUDED.source_timestamp, "
                + "outcome = EXCLUDED.outcome, normalized_phone = EXCLUDED.normalized_phone, "
                + "normalized_instagram = EXCLUDED.normalized_instagram, last_checked_at = NOW(), "
                + "retry_after = CASE WHEN EXCLUDED.outcome IN ('QUALIFIED') THEN NULL "
                + "ELSE NOW() + INTERVAL '30 days' END, details = EXCLUDED.details, "
                + "pipeline_version = EXCLUDED.pipeline_version";
        try (Connection con = BatchDataSource.open()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                for (PendingScan p : batch) {
                    int i = 1;
                    ps.setLong(i++, args.regionId());
                    if (ctx.targetId() > 0) ps.setLong(i++, ctx.targetId());
                    else ps.setNull(i++, java.sql.Types.BIGINT);
                    ps.setString(i++, ctx.canonicalNiche());
                    ps.setString(i++, p.osmType());
                    ps.setLong(i++, p.osmId());
                    OsmSourceTimestamp.bindInstant(ps, i++, p.sourceTimestamp());
                    ps.setString(i++, p.outcome());
                    ps.setString(i++, p.phone());
                    ps.setString(i++, p.ig());
                    ps.setString(i++, p.outcome());
                    ps.setString(i++, p.details() == null ? null : p.details().substring(0, Math.min(p.details().length(), 2000)));
                    ps.setString(i++, OsmCandidateScanStateRepository.PIPELINE_VERSION);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            con.commit();
        }
        if (m != null) {
            m.scanStateBatchWrites++;
            m.scanStateRowsWritten += batch.size();
        }
    }

    /** Tier 1: phone+ig diretos. Tier 2: phone ou ig direto + website. Tier 3: website+outro canal. Tier 4: website. Tier 5: resto. */
    static int priorityTier(OsmCandidate c) {
        JsonNode tags = c.tags();
        boolean phoneDirect = hasTag(tags, "contact:whatsapp", "whatsapp", "contact:phone", "phone",
                "contact:mobile", "mobile", "contact:sms", "sms");
        boolean igDirect = hasTag(tags, "contact:instagram", "instagram");
        boolean website = hasTag(tags, "contact:website", "website", "url");
        boolean otherChannel = hasTag(tags, "contact:facebook", "facebook", "contact:telegram", "telegram");
        if (phoneDirect && igDirect) return 1;
        if ((phoneDirect || igDirect) && website) return 2;
        if (website && otherChannel) return 3;
        if (website) return 4;
        return 5;
    }

    private static boolean hasTag(JsonNode tags, String... keys) {
        if (tags == null || !tags.isObject()) return false;
        for (String k : keys) {
            JsonNode v = tags.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return true;
        }
        return false;
    }

    record RunContext(long targetId, String canonicalNiche) {}

    private RunContext loadRunContext(Connection con) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT target_id, canonical_niche FROM osm_sync_runs WHERE id = ?")) {
            ps.setLong(1, args.syncRunId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("SYNC_RUN_NOT_FOUND");
                long tid = rs.getLong(1);
                if (rs.wasNull()) tid = args.targetId();
                String canonical = rs.getString(2);
                if (canonical == null || canonical.isBlank()) canonical = args.canonicalNiche();
                return new RunContext(tid, canonical);
            }
        }
    }

    private void markRunning(Connection con, RunContext ctx) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE osm_sync_runs SET status='RUNNING', started_at=NOW(), updated_at=NOW() "
                        + "WHERE id=? AND status='QUEUED'")) {
            ps.setLong(1, args.syncRunId());
            ps.executeUpdate();
        }
    }

    private void finalizeRun(Connection con, RunContext ctx, String status, OsmSyncMetrics m, String error)
            throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE osm_sync_runs SET status=?, finished_at=NOW(), updated_at=NOW(), "
                        + "objects_read=?, commercial_candidates=?, potential_niche_candidates=?, "
                        + "niche_confirmed=?, direct_phone=?, recovered_phone=?, direct_instagram=?, "
                        + "recovered_instagram=?, whatsapp_checks=?, whatsapp_verified_count=?, "
                        + "candidates_scanned=?, niche_matches=?, discarded_niche=?, discarded_no_phone=?, "
                        + "discarded_no_instagram=?, discarded_not_on_whatsapp=?, "
                        + "discarded_duplicate=?, discarded_duplicate_source=?, "
                        + "discarded_duplicate_phone=?, discarded_duplicate_instagram=?, "
                        + "qualified_saved=?, dataset_exhausted=?, error_message=? WHERE id=?")) {
            int i = 1;
            ps.setString(i++, status);
            ps.setLong(i++, m.objectsRead);
            ps.setLong(i++, m.commercialCandidates);
            ps.setLong(i++, m.potentialNicheCandidates);
            ps.setLong(i++, m.nicheConfirmed);
            ps.setLong(i++, m.directPhone);
            ps.setLong(i++, m.recoveredPhone);
            ps.setLong(i++, m.directInstagram);
            ps.setLong(i++, m.recoveredInstagram);
            ps.setLong(i++, m.whatsappChecks);
            ps.setLong(i++, m.whatsappVerified);
            ps.setLong(i++, m.candidatesScanned);
            ps.setLong(i++, m.nicheConfirmed);
            ps.setLong(i++, m.discardedNiche);
            ps.setLong(i++, m.discardedNoPhone);
            ps.setLong(i++, m.discardedNoInstagram);
            ps.setLong(i++, m.discardedNotOnWhatsApp);
            ps.setLong(i++, m.discardedDuplicateTotal());
            ps.setLong(i++, m.discardedDuplicateSource);
            ps.setLong(i++, m.discardedDuplicatePhone);
            ps.setLong(i++, m.discardedDuplicateInstagram);
            ps.setLong(i++, m.qualifiedSaved);
            ps.setBoolean(i++, m.datasetExhausted);
            ps.setString(i++, error == null ? null : error.substring(0, Math.min(error.length(), 2000)));
            ps.setLong(i++, args.syncRunId());
            ps.executeUpdate();
        }
    }

    private void updateTargetOutcome(Connection con, RunContext ctx, String status, OsmSyncMetrics m, String error)
            throws Exception {
        if (ctx.targetId() <= 0) return;
        boolean success = "SUCCESS".equals(status) || "PARTIAL".equals(status) || "EXHAUSTED".equals(status);
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE osm_catalog_targets SET last_attempt_at=NOW(), updated_at=NOW(), "
                        + "last_success_at=CASE WHEN ? THEN NOW() ELSE last_success_at END, "
                        + "last_error=CASE WHEN ? THEN NULL ELSE ? END WHERE id=?")) {
            ps.setBoolean(1, success);
            ps.setBoolean(2, success);
            ps.setString(3, error == null ? status : error.substring(0, Math.min(error.length(), 2000)));
            ps.setLong(4, ctx.targetId());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE osm_catalog_regions SET last_attempt_at=NOW(), updated_at=NOW(), "
                        + "last_success_at=CASE WHEN ? THEN NOW() ELSE last_success_at END, "
                        + "last_error=CASE WHEN ? THEN NULL ELSE ? END WHERE id=?")) {
            ps.setBoolean(1, success);
            ps.setBoolean(2, success);
            ps.setString(3, error == null ? status : error.substring(0, Math.min(error.length(), 2000)));
            ps.setLong(4, args.regionId());
            ps.executeUpdate();
        }
    }

    static boolean isCommercialOrContact(OsmCandidate c) {
        JsonNode tags = c.tags();
        if (tags == null || !tags.isObject()) return false;
        // Evidencia comercial estruturada OU contato oficial. Nome sozinho NAO basta.
        for (String k : new String[]{"shop", "amenity", "craft", "healthcare", "leisure", "office", "tourism"}) {
            JsonNode v = tags.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return true;
        }
        for (String k : new String[]{"phone", "contact:phone", "mobile", "contact:mobile",
                "contact:whatsapp", "whatsapp", "contact:sms", "sms",
                "website", "contact:website", "url",
                "instagram", "contact:instagram",
                "facebook", "contact:facebook", "telegram", "contact:telegram"}) {
            JsonNode v = tags.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return true;
        }
        return false;
    }

    private OsmQualifiedPoolRepository.QualifiedLead toQualifiedLead(
            OsmCandidate c, OfficialContactEnrichmentService.EnrichedContact e,
            NicheMapper.NicheConfirmation confirmation) throws Exception {
        String tagsJson = JSON.writeValueAsString(c.tags());
        String rawPhone = e.phone().raw();
        String rawIg = e.instagram().normalized();
        return new OsmQualifiedPoolRepository.QualifiedLead(
                c.osmType(), c.osmId(),
                c.name() == null || c.name().isBlank() ? c.stableKey() : c.name(), c.normalizedName(),
                c.latitude(), c.longitude(), c.address(),
                c.city(), c.state(), c.country(), c.countryCode(),
                rawPhone, e.phone().normalized(), e.website(),
                "@" + rawIg, rawIg, tagsJson, c.sourceTimestamp(),
                confirmation.evidenceType(),
                "canonical=" + strategy.canonicalName() + ";detail=" + confirmation.evidenceDetails(),
                e.phone().source(), e.phone().sourceUrl(),
                e.instagram().source(), e.instagram().sourceUrl());
    }

    private void logSummary(String finalStatus, OsmSyncMetrics m) {
        log.info("[osm-sync] sync_summary runId={} status={} objectsRead={} commercial={} potential={} "
                        + "scanSkipped={} fastRejected={} confirmed={} "
                        + "directPhone={} recoveredPhone={} directIg={} recoveredIg={} "
                        + "websiteCand={} websiteOk={} websiteFail={} timeout={} http403={} http404={} http429={} "
                        + "contactPages={} hubCand={} hubOk={} sameAs={} socialCand={} socialOk={} "
                        + "discNiche={} discNoPhone={} discNoIg={} discNoWpp={} discNoChannel={} "
                        + "dupSrc={} dupPhone={} dupIg={} "
                        + "wppChecks={} wppVerified={} qualifiedSaved={}/{} exhausted={} "
                        + "invalidTs={} techFail={} enrichTimeout={} chunks={} notScheduled={} "
                        + "scanBatches={} scanRows={} "
                        + "stageLoadMs={} stageReadMs={} stageEnrichMs={} stageWppPublishMs={} durationMs={}",
                args.syncRunId(), finalStatus, m.objectsRead, m.commercialCandidates,
                m.potentialNicheCandidates, m.scanStateSkipped, m.fastRejectedNoOfficialChannel,
                m.nicheConfirmed, m.directPhone, m.recoveredPhone, m.directInstagram, m.recoveredInstagram,
                m.websiteCandidates, m.websiteFetchSuccess, m.websiteFetchFailed, m.websiteTimeout,
                m.website403, m.website404, m.website429,
                m.contactPagesFetched, m.contactHubCandidates, m.contactHubSuccess,
                m.sameAsLinksFound, m.socialCandidates, m.socialSuccess,
                m.discardedNiche, m.discardedNoPhone, m.discardedNoInstagram, m.discardedNotOnWhatsApp,
                m.discardedNoOfficialChannel,
                m.discardedDuplicateSource, m.discardedDuplicatePhone, m.discardedDuplicateInstagram,
                m.whatsappChecks, m.whatsappVerified, m.qualifiedSaved, args.targetValid(), m.datasetExhausted,
                m.invalidSourceTimestamp, m.technicalEnrichmentFailures, m.enrichmentTimedOut,
                m.enrichmentChunks, m.candidatesNotScheduledAfterTargetReached,
                m.scanStateBatchWrites, m.scanStateRowsWritten,
                m.stageLoadScanStateMs, m.stageReadClassifyMs, m.stageEnrichMs, m.stageWppPublishMs, m.durationMs);
    }

    private static String env(String... names) {
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static int envInt(String name, int def) {
        try {
            String v = System.getenv(name);
            if (v == null || v.isBlank()) return def;
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static long envLong(String name, long def) {
        try {
            String v = System.getenv(name);
            if (v == null || v.isBlank()) return def;
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String safe(String s) {
        if (s == null) return "Erro desconhecido";
        return s.replaceAll("(?i)(password|token|secret|url|database_url|key)=[^\\s&]+", "$1=***");
    }

    private void logStage(String stage, String msg) {
        log.info("[osm-sync] stage={} runId={} targetId={} canonical={} msg={}",
                stage, args.syncRunId(), args.targetId(), args.canonicalNiche(), msg);
    }
}
