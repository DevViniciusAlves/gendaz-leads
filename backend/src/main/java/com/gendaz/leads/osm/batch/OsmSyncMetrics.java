package com.gendaz.leads.osm.batch;

public class OsmSyncMetrics {
    public long objectsRead;
    public long commercialCandidates;
    public long potentialNicheCandidates;
    public long scanStateSkipped;
    public long fastRejectedNoOfficialChannel;
    public long nicheConfirmed;
    public long directPhone;
    public long recoveredPhone;
    public long directInstagram;
    public long recoveredInstagram;
    // Website / hub / social
    public long websiteCandidates;
    public long websiteFetchSuccess;
    public long websiteFetchFailed;
    public long websiteTimeout;
    public long website403;
    public long website404;
    public long website429;
    public long contactPagesFetched;
    public long contactHubCandidates;
    public long contactHubSuccess;
    public long sameAsLinksFound;
    public long socialCandidates;
    public long socialSuccess;
    // Desc cartes
    public long discardedNiche;
    public long discardedNoPhone;
    public long discardedNoInstagram;
    public long discardedNotOnWhatsApp;
    public long discardedDuplicateSource;
    public long discardedDuplicatePhone;
    public long discardedDuplicateInstagram;
    public long discardedNoOfficialChannel;
    public long whatsappChecks;
    public long whatsappVerified;
    public long qualifiedSaved;
    public boolean datasetExhausted;
    public long candidatesScanned;
    public long durationMs;
    // Stage timings
    public long stageLoadScanStateMs;
    public long stageReadClassifyMs;
    public long stageEnrichMs;
    public long stageWppPublishMs;

    public long discardedDuplicateTotal() {
        return discardedDuplicateSource + discardedDuplicatePhone + discardedDuplicateInstagram;
    }
}
