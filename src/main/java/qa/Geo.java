package qa;

import java.util.List;

/** Geometry helpers. */
public final class Geo {
    public static final double EARTH_RADIUS_M = 6_371_000.0;

    private Geo() {}

    /** Great-circle distance in metres (correct for lat/lon). */
    public static double haversineMetres(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * What the legacy code does: treats degrees as a flat grid, 1 degree = 111.32 km in BOTH directions.
     * Wrong for longitude away from the equator (at 37.5N one degree of longitude is only ~88 km).
     */
    public static double flatDegreesMetres(double lat1, double lon1, double lat2, double lon2) {
        return Math.hypot(lat2 - lat1, lon2 - lon1) * 111_320.0;
    }

    /** Local flat projection in metres around a reference latitude; fine over a few tens of km. */
    public static double[] toXY(double lat, double lon, double refLat) {
        double x = Math.toRadians(lon) * EARTH_RADIUS_M * Math.cos(Math.toRadians(refLat));
        double y = Math.toRadians(lat) * EARTH_RADIUS_M;
        return new double[]{x, y};
    }

    public static double[] fromXY(double x, double y, double refLat) {
        double lat = Math.toDegrees(y / EARTH_RADIUS_M);
        double lon = Math.toDegrees(x / (EARTH_RADIUS_M * Math.cos(Math.toRadians(refLat))));
        return new double[]{lat, lon};
    }

    /** Shortest distance from a point to the road polyline, in metres. */
    public static double distanceToPolylineMetres(double lat, double lon, List<double[]> line) {
        double refLat = line.get(0)[0];
        double[] p = toXY(lat, lon, refLat);
        double best = Double.MAX_VALUE;
        for (int i = 1; i < line.size(); i++) {
            double[] a = toXY(line.get(i - 1)[0], line.get(i - 1)[1], refLat);
            double[] b = toXY(line.get(i)[0], line.get(i)[1], refLat);
            best = Math.min(best, pointToSegment(p, a, b));
        }
        return best;
    }

    private static double pointToSegment(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(p[0] - (a[0] + t * dx), p[1] - (a[1] + t * dy));
    }

    /**
     * Liang-Barsky clip of segment A->B against the zone rectangle.
     * Returns {t0, t1}: the part of the segment (0 = A, 1 = B) that lies inside the zone, or null if none.
     */
    public static double[] clipToZone(double latA, double lonA, double latB, double lonB, Config c) {
        double t0 = 0, t1 = 1;
        double dLon = lonB - lonA, dLat = latB - latA;
        double[] p = {-dLon, dLon, -dLat, dLat};
        double[] q = {lonA - c.zoneMinLon(), c.zoneMaxLon() - lonA, latA - c.zoneMinLat(), c.zoneMaxLat() - latA};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return null;          // parallel and outside
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) { if (r > t1) return null; if (r > t0) t0 = r; }
                else          { if (r < t0) return null; if (r < t1) t1 = r; }
            }
        }
        return t1 > t0 ? new double[]{t0, t1} : null;
    }
}
