package com.gendaz.leads.service;

import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmSyncRun;
import com.gendaz.leads.repository.OsmCatalogRegionRepository;
import com.gendaz.leads.repository.OsmSyncRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class OsmCatalogSyncStatusService {

    private final OsmSyncRunRepository syncRunRepository;
    private final OsmCatalogRegionRepository regionRepository;

    public OsmCatalogSyncStatusService(
            OsmSyncRunRepository syncRunRepository,
            OsmCatalogRegionRepository regionRepository
    ) {
        this.syncRunRepository = syncRunRepository;
        this.regionRepository = regionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDispatchFailed(
            Long syncRunId,
            String message
    ) {
        OsmSyncRun run = syncRunRepository.findById(syncRunId)
                .orElseThrow();

        run.setStatus("FAILED");
        run.setFinishedAt(Instant.now());
        run.setErrorMessage(message);

        syncRunRepository.save(run);

        OsmCatalogRegion region = run.getRegion();

        region.setLastAttemptAt(Instant.now());
        region.setLastError(message);

        regionRepository.save(region);
    }
}