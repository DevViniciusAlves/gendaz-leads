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
        boolean bboxValid
) {
    public SearchRegion rootRegion() {
        if (bboxValid && south < north && west < east) {
            return SearchRegion.root(south, west, north, east, lat, lon);
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
}