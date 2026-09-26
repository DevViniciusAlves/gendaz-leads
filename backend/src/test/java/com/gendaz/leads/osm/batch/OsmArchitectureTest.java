package com.gendaz.leads.osm.batch;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class OsmArchitectureTest {

    private static String jobSource() throws Exception {
        Path p = Paths.get("src/main/java/com/gendaz/leads/osm/batch/OsmSyncJob.java");
        if (!Files.exists(p)) {
            // Fallback quando executado de outro working dir.
            p = Paths.get("backend/src/main/java/com/gendaz/leads/osm/batch/OsmSyncJob.java");
        }
        return Files.readString(p);
    }

    @Test
    void scanStateBulkLoadedOnce() throws Exception {
        String src = jobSource();
        assertTrue(src.contains("scanRepo.loadAll("), "bulk load deve existir");
        assertFalse(src.contains("scanRepo.find("), "N+1 find por candidato proibido");
    }

    @Test
    void noDbOpenInsideCandidateLoop() throws Exception {
        String src = jobSource();
        int loopStart = src.indexOf("for (OsmCandidate candidate");
        int loopEnd = src.indexOf("tiered.sort(");
        assertTrue(loopStart > 0 && loopEnd > loopStart, "loop de classificacao deve existir");
        String loop = src.substring(loopStart, loopEnd);
        assertFalse(loop.contains("BatchDataSource.open()"),
                "BatchDataSource.open() nao pode ser chamado dentro do loop de candidatos");
    }

    @Test
    void noTransactionHeldDuringHttp() throws Exception {
        String src = jobSource();
        // Estrutura exigida: preflight fora de tx; publish usa conexao propria curta.
        assertTrue(src.contains("Preflight fora de qualquer transacao")
                || src.contains("ensureReady()"), "preflight deve existir fora de tx");
        assertTrue(src.contains("Publicacao atomica propria"), "publish deve usar tx propria curta");
        assertTrue(src.contains("flushScanBatch(ctx"), "scan batch deve usar conexao curta");
    }

    @Test
    void notPotentialHasNoDbWrite() throws Exception {
        String src = jobSource();
        int idx = src.indexOf("!NicheMapper.isPotential(");
        assertTrue(idx > 0, "branch !potential deve existir");
        String window = src.substring(idx, Math.min(src.length(), idx + 600));
        assertFalse(window.contains("PendingScan"), "!potential nao deve gerar scan-state write");
        assertFalse(window.contains("flushScanBatch"), "!potential nao deve dar flush");
    }

    @Test
    void noCastStringToTimestamptz() throws Exception {
        String src = jobSource();
        assertFalse(src.contains("CAST(? AS TIMESTAMPTZ)"),
                "runtime novo nao pode ter CAST(? AS TIMESTAMPTZ)");
        Path scan = Paths.get("src/main/java/com/gendaz/leads/osm/persistence/OsmCandidateScanStateRepository.java");
        if (!Files.exists(scan)) scan = Paths.get("backend/src/main/java/com/gendaz/leads/osm/persistence/OsmCandidateScanStateRepository.java");
        assertFalse(Files.readString(scan).contains("CAST(? AS TIMESTAMPTZ)"));
        Path pool = Paths.get("src/main/java/com/gendaz/leads/osm/persistence/OsmQualifiedPoolRepository.java");
        if (!Files.exists(pool)) pool = Paths.get("backend/src/main/java/com/gendaz/leads/osm/persistence/OsmQualifiedPoolRepository.java");
        assertFalse(Files.readString(pool).contains("CAST(? AS TIMESTAMPTZ)"));
    }
}
