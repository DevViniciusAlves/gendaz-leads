package com.gendaz.leads.osm.batch;

public class OsmSyncMetrics {
    public long objectsRead;
    public long commercialCandidates;
    public long potentialNicheCandidates;
    public long nicheConfirmed;
    public long directPhone;
    public long recoveredPhone;
    public long directInstagram;
    public long recoveredInstagram;
    public long whatsappChecks;
    public long whatsappVerified;
    public long discardedNiche;
    public long discardedNoPhone;
    public long discardedNoInstagram;
    public long discardedNotOnWhatsApp;
    public long discardedDuplicateSource;
    public long discardedDuplicatePhone;
    public long discardedDuplicateInstagram;
    public long qualifiedSaved;
    public boolean datasetExhausted;
    public long candidatesScanned;

    public long discardedDuplicateTotal() {
        return discardedDuplicateSource + discardedDuplicatePhone + discardedDuplicateInstagram;
    }
}
