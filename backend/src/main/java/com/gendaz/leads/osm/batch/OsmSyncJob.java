package com.gendaz.leads.osm.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.osm.discovery.OsmCandidate;
import com.gendaz.leads.osm.discovery.OsmGeoJsonSeqReader;
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
 * Enrichment oficial acontece ANTES da rejeicao definitiva do nicho.
 * Instagram + telefone + WhatsApp obrigatorios. Dedup antes do WPP e antes de contar.
 *
 * Performance (java-osm-v2):
 * - scan state carregado 1x (bulk) + lookup in-memory, zero conexao por candidato
 * - potential antes de qualquer DB
 * - scan rejections em batch (sem commit por candidato)
 * - enrichment paralelo bounded (OSM_SYNC_WEBSITE_MAX_WORKERS)
 * - timeouts HTTP configuraveis
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

    record PendingScan(String osmType, long osmId, String sourceTimestamp, String outcome,
                       String phone, String ig, String details) {}

    record TieredCandidate(OsmCandidate candidate, int tier) {}

    record EnrichedItem(TieredCandidate tiered,
                        OfficialContactEnrichmentService.EnrichedContact enriched) {}

    public OsmSyncMetrics run() throws Exception {
        OsmSyncMetrics m = new OsmSyncMetrics();
        long jobStart = System.currentTimeMillis();
        String wppUrl = env("OSM_SYNC_WHATSAPP_SERVICE_URL", "WHATSAPP_SERVICE_URL");
        String wppToken = env("OSM_SYNC_WHATSAPP_INTERNAL_TOKEN", "WHATSAPP_INTERNAL_TOKEN");
        int workers = envInt("OSM_SYNC_WEBSITE_MAX_WORKERS", 8);
        int connectTimeout = envInt("OSM_SYNC_CONNECT_TIMEOUT_MS", 2000);
        int readTimeout = envInt("OSM_SYNC_READ_TIMEOUT_MS", 3500);
        if (workers < 1) workers = 1;
        if (workers > 16) workers = 16;

        try (Connection con = BatchDataSource.open()) {
            con.setAutoCommit(false);
            RunContext ctx = loadRunContext(con);
            markRunning(con, ctx);
            con.commit();

            if (!args.dryRun()) {
                new WhatsAppPreflightService(wppUrl, wppToken).ensureReady();
                logStage("PRECHECK", "whatsapp preflight ok");
            }

            OsmDeduplicator dedup = new OsmDeduplicator();
            dedup.loadPool(con, args.regionId(), args.targetId());
            OsmCandidateScanStateRepository scanRepo = new OsmCandidateScanStateRepository();
            OsmQualifiedPoolRepository poolRepo = new OsmQualifiedPoolRepository();
            OfficialWebsiteFetcher fetcher = new OfficialWebsiteFetcher(connectTimeout, readTimeout, 500_000);
            OfficialContactEnrichmentService enrichment =
                    new OfficialContactEnrichmentService(fetcher, args.countryCode());
            WhatsAppRecipientValidationClient wpp =
                    new WhatsAppRecipientValidationClient(wppUrl, wppToken, 3, 350);

            List<OsmQualifiedPoolRepository.QualifiedLead> staged = new ArrayList<>();
            List<PendingScan> pendingScans = new ArrayList<>();

            // Stage 1: bulk load scan state (1 query).
            long t0 = System.currentTimeMillis();
            Map<String, OsmCandidateScanStateRepository.ScanEntry> scanStates =
                    scanRepo.loadAll(con, args.regionId(), ctx.targetId(),
                            ctx.canonicalNiche(), OsmCandidateScanStateRepository.PIPELINE_VERSION);
            m.stageLoadScanStateMs = System.currentTimeMillis() - t0;
            logStage("LOAD_SCAN_STATE", "loaded=" + scanStates.size()
                    + " durationMs=" + m.stageLoadScanStateMs);

            // Stage 2: read + commercial + potential + scan-skip in-memory + priority.
            t0 = System.currentTimeMillis();
            List<TieredCandidate> tiered = new ArrayList<>();
            try (OsmGeoJsonSeqReader reader = new OsmGeoJsonSeqReader(
                    Path.of(args.input()), args.city(), args.state(), null, args.countryCode())) {
                for (OsmCandidate candidate : reader) {
                    if (candidate == null) continue;
                    m.objectsRead++;
                    m.candidatesScanned++;

                    if (!isCommercialOrContact(candidate)) continue;
                    m.commercialCandidates++;

                    if (!NicheMapper.isPotential(strategy, candidate.tags(), candidate.normalizedName())) {
                        m.discardedNiche++;
                        pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                                candidate.timestamp(), "NICHE_NOT_CONFIRMED", null, null, "not potential"));
                        flushScanBatchIfNeeded(con, scanRepo, ctx, pendingScans, false);
                        continue;
                    }
                    m.potentialNicheCandidates++;

                    OsmCandidateScanStateRepository.ScanEntry entry =
                            scanStates.get(candidate.stableKey());
                    if (entry != null && scanRepo.shouldSkip(entry, candidate.timestamp())) {
                        m.scanStateSkipped++;
                        continue;
                    }

                    // Fast reject: sem canal oficial -> zero HTTP.
                    if (!OfficialContactEnrichmentService.hasOfficialContactChannel(candidate.tags())) {
                        m.fastRejectedNoOfficialChannel++;
                        m.discardedNoOfficialChannel++;
                        pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                                candidate.timestamp(), "NO_OFFICIAL_CONTACT_CHANNEL", null, null,
                                "sem canal oficial"));
                        flushScanBatchIfNeeded(con, scanRepo, ctx, pendingScans, false);
                        continue;
                    }

                    tiered.add(new TieredCandidate(candidate, priorityTier(candidate)));
                }
            }
            tiered.sort(Comparator.comparingInt(TieredCandidate::tier));
            m.stageReadClassifyMs = System.currentTimeMillis() - t0;
            logStage("READ_CLASSIFY", "tiered=" + tiered.size()
                    + " durationMs=" + m.stageReadClassifyMs);

            // Stage 3: parallel enrichment (bounded), preservando ordem de prioridade.
            t0 = System.currentTimeMillis();
            List<EnrichedItem> enrichedList = enrichParallel(enrichment, tiered, workers);
            m.stageEnrichMs = System.currentTimeMillis() - t0;
            logStage("ENRICH", "enriched=" + enrichedList.size()
                    + " workers=" + workers + " durationMs=" + m.stageEnrichMs);

            // Stage 4: confirm niche -> contacts -> dedup (antes do WPP) -> WPP -> stage.
            t0 = System.currentTimeMillis();
            for (EnrichedItem item : enrichedList) {
                if (m.qualifiedSaved >= args.targetValid()) break;
                OsmCandidate candidate = item.tiered().candidate();
                OfficialContactEnrichmentService.EnrichedContact enriched = item.enriched();

                if (enriched.directPhone()) m.directPhone++;
                else if (enriched.phone() != null) m.recoveredPhone++;
                if (enriched.directInstagram()) m.directInstagram++;
                else if (enriched.instagram() != null) m.recoveredInstagram++;

                NicheMapper.NicheConfirmation confirmation = NicheMapper.confirm(
                        strategy, candidate.tags(), enriched.officialText());
                if (!confirmation.confirmed()) {
                    m.discardedNiche++;
                    pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                            candidate.timestamp(), "NICHE_NOT_CONFIRMED", null, null, "confirm failed"));
                    flushScanBatchIfNeeded(con, scanRepo, ctx, pendingScans, false);
                    continue;
                }
                m.nicheConfirmed++;

                if (enriched.instagram() == null) {
                    m.discardedNoInstagram++;
                    pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                            candidate.timestamp(), "NO_INSTAGRAM",
                            enriched.phone() == null ? null : enriched.phone().normalized(),
                            null, "sem instagram oficial"));
                    flushScanBatchIfNeeded(con, scanRepo, ctx, pendingScans, false);
                    continue;
                }
                if (enriched.phone() == null) {
                    m.discardedNoPhone++;
                    pendingScans.add(new PendingScan(candidate.osmType(), candidate.osmId(),
                            candidate.timestamp(), "NO_PHONE", null,
                            enriched.instagram().normalized(), "sem telefone oficial"));
                    flushScanBatchIfNeeded(con, scanRepo, ctx, pendingScans, false);
                    continue;
                }

                // Dedup ANTES do WPP (WPP e operacao externa cara).
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
                            candidate.timestamp(), "DUPLICATE_PHONE",
                            enriched.phone().normalized(), enriched.instagram().normalized(),
                            "duplicate " + dup.kind()));
                    flushScanBatchIfNeeded(con, scanRepo, ctx, pendingScans, false);
                    continue;
                }

                // WPP recipient check: falha tecnica aborta como FAILED, nunca como descarte.
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
                            candidate.timestamp(), "NOT_ON_WHATSAPP",
                            enriched.phone().normalized(), enriched.instagram().normalized(),
                            "nao existe no WhatsApp"));
                    flushScanBatchIfNeeded(con, scanRepo, ctx, pendingScans, false);
                    continue;
                }
                m.whatsappVerified++;

                OsmQualifiedPoolRepository.QualifiedLead lead = toQualifiedLead(candidate, enriched, confirmation);
                staged.add(lead);
                dedup.markSeen(candidate.osmType(), candidate.osmId(),
                        enriched.phone().normalized(), enriched.instagram().normalized());
                m.qualifiedSaved++;
                log.info("[osm-sync] sync_summary runId={} targetId={} qualifiedSaved={}/{} candidate={} phoneSrc={} igSrc={}",
                        args.syncRunId(), args.targetId(), m.qualifiedSaved, args.targetValid(),
                        candidate.stableKey(),
                        enriched.phone().source(), enriched.instagram().source());
            }

            // Copia metricas do fetcher/enrichment para o summary.
            m.websiteCandidates = enrichment.websiteCandidates.get();
            m.websiteFetchSuccess = fetcher.fetchSuccess.get();
            m.websiteFetchFailed = fetcher.fetchFailed.get();
            m.websiteTimeout = fetcher.fetchTimeout.get();
            m.website403 = fetcher.fetch403.get();
            m.website404 = fetcher.fetch404.get();
            m.website429 = fetcher.fetch429.get();
            m.contactPagesFetched = enrichment.contactPagesFetched.get();
            m.contactHubCandidates = enrichment.contactHubCandidates.get();
            m.contactHubSuccess = enrichment.contactHubSuccess.get();
            m.sameAsLinksFound = enrichment.sameAsLinksFound.get();
            m.socialCandidates = enrichment.socialCandidates.get();
            m.socialSuccess = enrichment.socialSuccess.get();

            m.datasetExhausted = m.qualifiedSaved < args.targetValid();
            String finalStatus = m.qualifiedSaved >= args.targetValid() ? "SUCCESS"
                    : m.qualifiedSaved > 0 ? "PARTIAL" : "EXHAUSTED";
            String finalError = "EXHAUSTED".equals(finalStatus) ? null : null;

            // Flush restante das rejeicoes antes da publicacao.
            flushScanBatchIfNeeded(con, scanRepo, ctx, pendingScans, true);
            con.commit();

            // Publicacao atomica.
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
                    finalizeRun(con2, ctx, finalStatus, m, finalError);
                    updateTargetOutcome(con2, ctx, finalStatus, m, finalError);
                    con2.commit();
                } catch (Exception e) {
                    con2.rollback();
                    throw e;
                }
            }
            m.stageWppPublishMs = System.currentTimeMillis() - t0;
            m.durationMs = System.currentTimeMillis() - jobStart;
            log.info("[osm-sync] sync_summary runId={} status={} objectsRead={} commercial={} potential={} "
                            + "scanSkipped={} fastRejected={} confirmed={} "
                            + "directPhone={} recoveredPhone={} directIg={} recoveredIg={} "
                            + "websiteCand={} websiteOk={} websiteFail={} timeout={} http403={} http404={} http429={} "
                            + "contactPages={} hubCand={} hubOk={} sameAs={} socialCand={} socialOk={} "
                            + "discNiche={} discNoPhone={} discNoIg={} discNoWpp={} discNoChannel={} "
                            + "dupSrc={} dupPhone={} dupIg={} "
                            + "wppChecks={} wppVerified={} qualifiedSaved={}/{} exhausted={} "
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
                    m.stageLoadScanStateMs, m.stageReadClassifyMs, m.stageEnrichMs, m.stageWppPublishMs, m.durationMs);
            return m;
        } catch (WhatsAppInfrastructureException e) {
            try (Connection con = BatchDataSource.open()) {
                RunContext ctx = loadRunContext(con);
                con.setAutoCommit(false);
                try {
                    finalizeRun(con, ctx, "FAILED", m, e.getCode() + ": " + e.getMessage());
                    updateTargetOutcome(con, ctx, "FAILED", m, e.getCode() + ": " + e.getMessage());
                    con.commit();
                } catch (Exception ex) {
                    con.rollback();
                }
            }
            throw e;
        } catch (Exception e) {
            try (Connection con = BatchDataSource.open()) {
                try {
                    RunContext ctx = loadRunContext(con);
                    con.setAutoCommit(false);
                    try {
                        finalizeRun(con, ctx, "FAILED", m, safe(e.getMessage()));
                        updateTargetOutcome(con, ctx, "FAILED", m, safe(e.getMessage()));
                        con.commit();
                    } catch (Exception ex) {
                        con.rollback();
                    }
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    private List<EnrichedItem> enrichParallel(OfficialContactEnrichmentService enrichment,
                                             List<TieredCandidate> tiered, int workers) {
        if (tiered.isEmpty()) return List.of();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<CompletableFuture<EnrichedItem>> futures = new ArrayList<>(tiered.size());
            for (TieredCandidate t : tiered) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    OfficialContactEnrichmentService.EnrichedContact e = enrichment.enrich(t.candidate());
                    return new EnrichedItem(t, e);
                }, pool));
            }
            List<EnrichedItem> out = new ArrayList<>(tiered.size());
            for (CompletableFuture<EnrichedItem> f : futures) {
                try {
                    out.add(f.get(120, TimeUnit.SECONDS));
                } catch (Exception e) {
                    // Falha de enrichment isolada nao derruba o run; item sem contatos sera descartado.
                    try {
                        TieredCandidate t = tiered.get(out.size());
                        out.add(new EnrichedItem(t, new OfficialContactEnrichmentService.EnrichedContact(
                                null, null, null, "", false, false)));
                    } catch (Exception ignored) {}
                }
            }
            return out;
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void flushScanBatchIfNeeded(Connection con, OsmCandidateScanStateRepository repo,
                                       RunContext ctx, List<PendingScan> pending, boolean force) throws Exception {
        if (pending.isEmpty()) return;
        if (!force && pending.size() < SCAN_BATCH_SIZE) return;
        String sql = "INSERT INTO osm_candidate_scan_state "
                + "(region_id, target_id, canonical_niche, osm_type, osm_id, source_timestamp, "
                + "outcome, normalized_phone, normalized_instagram, last_checked_at, retry_after, details, pipeline_version) "
                + "VALUES (?, ?, ?, ?, ?, CAST(? AS TIMESTAMPTZ), ?, ?, ?, NOW(), "
                + "CASE WHEN ? IN ('QUALIFIED') THEN NULL ELSE NOW() + INTERVAL '30 days' END, ?, ?) "
                + "ON CONFLICT (region_id, canonical_niche, osm_type, osm_id) DO UPDATE SET "
                + "target_id = EXCLUDED.target_id, source_timestamp = EXCLUDED.source_timestamp, "
                + "outcome = EXCLUDED.outcome, normalized_phone = EXCLUDED.normalized_phone, "
                + "normalized_instagram = EXCLUDED.normalized_instagram, last_checked_at = NOW(), "
                + "retry_after = CASE WHEN EXCLUDED.outcome IN ('QUALIFIED') THEN NULL "
                + "ELSE NOW() + INTERVAL '30 days' END, details = EXCLUDED.details, "
                + "pipeline_version = EXCLUDED.pipeline_version";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (PendingScan p : pending) {
                int i = 1;
                ps.setLong(i++, args.regionId());
                if (ctx.targetId() > 0) ps.setLong(i++, ctx.targetId());
                else ps.setNull(i++, java.sql.Types.BIGINT);
                ps.setString(i++, ctx.canonicalNiche());
                ps.setString(i++, p.osmType());
                ps.setLong(i++, p.osmId());
                ps.setString(i++, p.sourceTimestamp());
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
        pending.clear();
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

    private record RunContext(long targetId, String canonicalNiche) {}

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
                "@" + rawIg, rawIg, tagsJson, c.timestamp(),
                confirmation.evidenceType(),
                "canonical=" + strategy.canonicalName() + ";detail=" + confirmation.evidenceDetails(),
                e.phone().source(), e.phone().sourceUrl(),
                e.instagram().source(), e.instagram().sourceUrl());
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

    private static String safe(String s) {
        if (s == null) return "Erro desconhecido";
        return s.replaceAll("(?i)(password|token|secret|url|database_url|key)=[^\\s&]+", "$1=***");
    }

    private void logStage(String stage, String msg) {
        log.info("[osm-sync] stage={} runId={} targetId={} canonical={} msg={}",
                stage, args.syncRunId(), args.targetId(), args.canonicalNiche(), msg);
    }
}
