package com.gendaz.leads.service.provider;

public record GeoScope(
        double lat,
        double lon,
        String city,
        String state,
        String country,
        String countryCode,
        double south,
        double west,
        double north,
        double east,
        boolean bboxValid,
        String osmType,
        long osmId
) {

    // Compatibilidade temporária para callers/testes antigos.
    public GeoScope(
            double lat,
            double lon,
            String city,
            String state,
            String country,
            String countryCode,
            double south,
            double west,
            double north,
            double east,
            boolean bboxValid
    ) {
        this(
                lat,
                lon,
                city,
                state,
                country,
                countryCode,
                south,
                west,
                north,
                east,
                bboxValid,
                null,
                -1L
        );
    }

    public SearchRegion rootRegion() {
        if (bboxValid && south < north && west < east) {
            return SearchRegion.root(
                    south,
                    west,
                    north,
                    east,
                    lat,
                    lon
            );
        }

        double delta = 0.18d;

        return SearchRegion.root(
                lat - delta,
                lon - delta,
                lat + delta,
                lon + delta,
                lat,
                lon
        );
    }

    public boolean hasAdminAreaCandidate() {
        return osmId > 0L
                && osmType != null
                && osmType.equalsIgnoreCase("relation");
    }
}