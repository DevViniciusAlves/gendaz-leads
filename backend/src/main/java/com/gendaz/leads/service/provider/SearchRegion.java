package com.gendaz.leads.service.provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public record SearchRegion(
        double south,
        double west,
        double north,
        double east,
        int depth,
        double distanceFromCityCenterKm
) {

    public static SearchRegion root(
            double south,
            double west,
            double north,
            double east,
            double cityLat,
            double cityLon
    ) {
        double centerLat = (south + north) / 2.0d;
        double centerLon = (west + east) / 2.0d;

        return new SearchRegion(
                south,
                west,
                north,
                east,
                0,
                haversineKm(cityLat, cityLon, centerLat, centerLon)
        );
    }

    public String bbox() {
        return String.format(
                Locale.US,
                "%f,%f,%f,%f",
                south,
                west,
                north,
                east
        );
    }

    public double centerLat() {
        return (south + north) / 2.0d;
    }

    public double centerLon() {
        return (west + east) / 2.0d;
    }

    public double northSouthKm() {
        return haversineKm(south, centerLon(), north, centerLon());
    }

    public double eastWestKm() {
        return haversineKm(centerLat(), west, centerLat(), east);
    }

    public boolean canSplit(int maxDepth, double minEdgeKm) {
        if (depth >= maxDepth) return false;
        return northSouthKm() > minEdgeKm || eastWestKm() > minEdgeKm;
    }

    public List<SearchRegion> split(
            double cityLat,
            double cityLon
    ) {
        double midLat = (south + north) / 2.0d;
        double midLon = (west + east) / 2.0d;

        List<SearchRegion> out = new ArrayList<>(4);

        add(out, south, west, midLat, midLon, cityLat, cityLon);
        add(out, south, midLon, midLat, east, cityLat, cityLon);
        add(out, midLat, west, north, midLon, cityLat, cityLon);
        add(out, midLat, midLon, north, east, cityLat, cityLon);

        out.sort(
                Comparator
                        .comparingDouble(SearchRegion::distanceFromCityCenterKm)
                        .thenComparingDouble(SearchRegion::south)
                        .thenComparingDouble(SearchRegion::west)
        );

        return out;
    }

    private void add(
            List<SearchRegion> out,
            double s,
            double w,
            double n,
            double e,
            double cityLat,
            double cityLon
    ) {
        if (s >= n || w >= e) return;

        double cLat = (s + n) / 2.0d;
        double cLon = (w + e) / 2.0d;

        out.add(new SearchRegion(
                s,
                w,
                n,
                e,
                depth + 1,
                haversineKm(cityLat, cityLon, cLat, cLon)
        ));
    }

    static double haversineKm(
            double lat1,
            double lon1,
            double lat2,
            double lon2
    ) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a =
                Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        + Math.cos(Math.toRadians(lat1))
                        * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2)
                        * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return 6371.0d * c;
    }
}