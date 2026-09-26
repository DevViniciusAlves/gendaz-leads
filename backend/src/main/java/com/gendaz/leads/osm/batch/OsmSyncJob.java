package com.gendaz.leads.osm.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.osm.discovery.OsmCandidate;
import com.gendaz.leads.osm.discovery.OsmGeoJsonSeqReader;
import com.gendaz.leads.osm.enrichment.InstagramResolver;
import com.gendaz.leads.osm.enrichment.OfficialContactEnrichmentService;
import com.gendaz.leads.osm.enrichment.OfficialWebsiteFetcher;
import com.gendaz.leads.osm.enrichment.PhoneResolver;
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
import java.util.List;

/**
 * Motor target-50 em Java:
 * qualifiedSaved == 50 => SUCCESS; 1..49 com dataset esgotado => PARTIAL;
 * 0 com dataset esgotado => EXHAUSTED; erro tecnico real => FAILED.
 * Enrichment oficial acontece ANTES da rejeicao definitiva do nicho.
 * Instagram + telefone + WhatsApp obrigatorios. Dedup antes de contar.
 */
public class OsmSyncJob {

    private static final Logger log = LoggerFactory.getLogger(OsmSyncJob.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OsmSyncArguments args;
    private final NicheMapper.NicheStrategy strategy;

    public OsmSyncJob(OsmSyncArguments args) {
        this.args = args;
        this.strategy = NicheMapper.resolve(args.canonicalNiche());
    }

    public OsmSyncMetrics run() throws Exception {
        OsmSyncMetrics m = new OsmSyncMetrics();
        String wppUrl = env("OSM_SYNC_WHATSAPP_SERVICE_URL", "WHATSAPP_SERVICE_URL");
        String wppToken = env("OSM_SYNC_WHATSAPP_INTERNAL_TOKEN", "WHATSAPP_INTERNAL_TOKEN");

        try (Connection con = BatchDataSource.open()) {
            con.setAutoCommit(false);
            RunContext ctx = loadRunContext(con);
            markRunning(con, ctx);
            con.commit();

            // Preflight Java: nunca reseta sessao, nunca envia mensagem.
            if (!args.dryRun()) {
                new WhatsAppPreflightService(wppUrl, wppToken).ensureReady();
                logStage("PRECHECK", "whatsapp preflight ok");
            }

            OsmDeduplicator dedup = new OsmDeduplicator();
            dedup.loadPool(con, args.regionId());
            OsmCandidateScanStateRepository scanRepo = new OsmCandidateScanStateRepository();
            OsmQualifiedPoolRepository poolRepo = new OsmQualifiedPoolRepository();
            OfficialWebsiteFetcher fetcher = new OfficialWebsiteFetcher(2000, 8000, 500_000);
            OfficialContactEnrichmentService enrichment =
                    new OfficialContactEnrichmentService(fetcher, args.countryCode());
            WhatsAppRecipientValidationClient wpp =
                    new WhatsAppRecipientValidationClient(wppUrl, wppToken, 3, 350);

            List<OsmQualifiedPoolRepository.QualifiedLead> staged = new ArrayList<>();

            try (OsmGeoJsonSeqReader reader = new OsmGeoJsonSeqReader(
                    Path.of(args.input()), args.city(), args.state(), null, args.countryCode())) {
                for (OsmCandidate candidate : reader) {
                    if (candidate == null) continue;
                    if (m.qualifiedSaved >= args.targetValid()) break;
                    m.objectsRead++;
                    m.candidatesScanned++;

                    if (!isCommercialOrContact(candidate)) continue;
                    m.commercialCandidates++;

                    // Scan state: TTL 30d, reavalia se source_timestamp mudou.
                    OsmCandidateScanStateRepository.ScanEntry entry;
                    try (Connection c2 = BatchDataSource.open()) {
                        entry = scanRepo.find(c2, args.regionId(), ctx.canonicalNiche(),
                                candidate.osmType(), candidate.osmId());
                    }
                    if (entry != null && scanRepo.shouldSkip(entry, candidate.timestamp())) {
                        continue;
                    }

                    if (!NicheMapper.isPotential(strategy, candidate.tags(), candidate.normalizedName())) {
                        m.discardedNiche++;
                        scan(con, scanRepo, candidate, "NICHE_NOT_CONFIRMED", null, null, "not potential");
                        continue;
                    }
                    m.potentialNicheCandidates++;

                    OfficialContactEnrichmentService.EnrichedContact enriched = enrichment.enrich(candidate);
                    if (enriched.directPhone()) m.directPhone++;
                    else if (enriched.phone() != null) m.recoveredPhone++;
                    if (enriched.directInstagram()) m.directInstagram++;
                    else if (enriched.instagram() != null) m.recoveredInstagram++;

                    NicheMapper.NicheConfirmation confirmation = NicheMapper.confirm(
                            strategy, candidate.tags(), enriched.officialText());
                    if (!confirmation.confirmed()) {
                        m.discardedNiche++;
                        scan(con, scanRepo, candidate, "NICHE_NOT_CONFIRMED", null, null,
                                "confirm failed");
                        continue;
                    }
                    m.nicheConfirmed++;

                    if (enriched.instagram() == null) {
                        m.discardedNoInstagram++;
                        scan(con, scanRepo, candidate, "NO_INSTAGRAM",
                                enriched.phone() == null ? null : enriched.phone().normalized(),
                                null, "sem instagram oficial");
                        continue;
                    }
                    if (enriched.phone() == null) {
                        m.discardedNoPhone++;
                        scan(con, scanRepo, candidate, "NO_PHONE", null,
                                enriched.instagram().normalized(), "sem telefone oficial");
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
                        scan(con, scanRepo, candidate, "NOT_ON_WHATSAPP",
                                enriched.phone().normalized(), enriched.instagram().normalized(),
                                "nao existe no WhatsApp");
                        continue;
                    }
                    m.whatsappVerified++;

                    OsmDeduplicator.DuplicateResult dup = dedup.check(candidate.osmType(), candidate.osmId(),
                            enriched.phone().normalized(), enriched.instagram().normalized());
                    if (dup.duplicate()) {
                        switch (dup.kind()) {
                            case SOURCE -> m.discardedDuplicateSource++;
                            case PHONE, GLOBAL -> m.discardedDuplicatePhone++;
                            case INSTAGRAM -> m.discardedDuplicateInstagram++;
                            default -> m.discardedDuplicateSource++;
                        }
                        scan(con, scanRepo, candidate, "DUPLICATE_PHONE",
                                enriched.phone().normalized(), enriched.instagram().normalized(),
                                "duplicate " + dup.kind());
                        continue;
                    }

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
            }

            m.datasetExhausted = m.qualifiedSaved < args.targetValid();
            String finalStatus = m.qualifiedSaved >= args.targetValid() ? "SUCCESS"
                    : m.qualifiedSaved > 0 ? "PARTIAL" : "EXHAUSTED";

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
                    finalizeRun(con2, ctx, finalStatus, m, null);
                    updateTargetOutcome(con2, ctx, finalStatus, m, null);
                    con2.commit();
                } catch (Exception e) {
                    con2.rollback();
                    throw e;
                }
            }
            log.info("[osm-sync] sync_summary runId={} status={} objectsRead={} commercial={} potential={} confirmed={} "
                            + "wppChecks={} wppVerified={} qualifiedSaved={}/{} exhausted={}",
                    args.syncRunId(), finalStatus, m.objectsRead, m.commercialCandidates,
                    m.potentialNicheCandidates, m.nicheConfirmed, m.whatsappChecks,
                    m.whatsappVerified, m.qualifiedSaved, args.targetValid(), m.datasetExhausted);
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
        // Nunca converte PARTIAL/EXHAUSTED em FAILED por etapa posterior: status ja resolvido acima.
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
        boolean success = "SUCCESS".equals(status) || "PARTIAL".equals(status);
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
        // Region: mantem compatibilidade do painel legado.
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

    private void scan(Connection con, OsmCandidateScanStateRepository repo, OsmCandidate c,
                      String outcome, String phone, String ig, String details) {
        try {
            repo.upsert(con, args.regionId(), null, strategy.canonicalName(),
                    c.osmType(), c.osmId(), c.timestamp(), outcome, phone, ig, details);
            con.commit();
        } catch (Exception e) {
            try {
                con.rollback();
            } catch (Exception ignored) {}
        }
    }

    private boolean isCommercialOrContact(OsmCandidate c) {
        JsonNode tags = c.tags();
        if (tags == null || !tags.isObject()) return false;
        for (String k : new String[]{"shop", "amenity", "craft", "healthcare", "leisure", "office", "tourism",
                "phone", "contact:phone", "mobile", "contact:mobile", "contact:whatsapp", "whatsapp",
                "website", "contact:website", "url", "instagram", "contact:instagram"}) {
            JsonNode v = tags.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return true;
        }
        return c.name() != null && !c.name().isBlank();
    }

    private OsmQualifiedPoolRepository.QualifiedLead toQualifiedLead(
            OsmCandidate c, OfficialContactEnrichmentService.EnrichedContact e,
            NicheMapper.NicheConfirmation confirmation) throws Exception {
        String tagsJson = JSON.writeValueAsString(c.tags());
        String rawPhone = e.phone().raw();
        String rawIg = e.instagram().normalized();
        return new OsmQualifiedPoolRepository.QualifiedLead(
                c.osmType(), c.osmId(),
                c.name().isBlank() ? c.stableKey() : c.name(), c.normalizedName(),
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

    private static String safe(String s) {
        if (s == null) return "Erro desconhecido";
        return s.replaceAll("(?i)(password|token|secret|url|database_url|key)=[^\\s&]+", "$1=***");
    }

    private void logStage(String stage, String msg) {
        log.info("[osm-sync] stage={} runId={} targetId={} canonical={} msg={}",
                stage, args.syncRunId(), args.targetId(), args.canonicalNiche(), msg);
    }
}
