package qa;

import java.time.ZoneOffset;
import java.util.List;

/**
 * Business rules and geography. All values for the SYNTHETIC case.
 *
 * road        : the road path as a polyline of {lat, lon} points
 * zone        : campaign zone, an axis-aligned lat/lon rectangle (simplification, see directive.md)
 * reportOffset: daily report is cut at 00:00 in this offset (UTC+9)
 */
public record Config(List<double[]> road,
                     double zoneMinLat, double zoneMaxLat,
                     double zoneMinLon, double zoneMaxLon,
                     ZoneOffset reportOffset,
                     double maxOffRoadMetres,
                     double maxSpeedKmh) {

    /** The synthetic city used by the generator: an L-shaped 3-leg road, zone covers the middle. */
    public static Config standard() {
        return new Config(
                List.of(new double[]{37.5000, 127.0000},
                        new double[]{37.5000, 127.0600},
                        new double[]{37.5450, 127.0600},
                        new double[]{37.5450, 127.1200}),
                37.4900, 37.5300,
                127.0300, 127.0900,
                ZoneOffset.ofHours(9),
                100.0,
                150.0);
    }

    public boolean inZone(double lat, double lon) {
        return lat >= zoneMinLat && lat <= zoneMaxLat && lon >= zoneMinLon && lon <= zoneMaxLon;
    }

    public double distanceToRoadMetres(double lat, double lon) {
        return Geo.distanceToPolylineMetres(lat, lon, road);
    }
}
