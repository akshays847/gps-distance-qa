package qa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Generates a SYNTHETIC, clearly labelled ping dataset with known faults.
 *
 * Output (all in ./data):
 *   pings_SYNTHETIC.csv         - the raw feed, in the order the server received it
 *   fault_labels_SYNTHETIC.csv  - answer key: which ping ids were deliberately faulted and how
 *   ground_truth_SYNTHETIC.csv  - true in-zone km per driver per local day, computed from the
 *                                 clean pings BEFORE any fault was injected (independent method:
 *                                 fine sampling, not the clipping maths used by the fix)
 *
 * Deterministic: same seed -> same file.
 */
public class DataGenerator {

    public static final long SEED = 20261111L;
    static final int PING_INTERVAL_S = 10;

    // Fault rates (per eligible ping)
    static final double P_OFF_ROAD = 0.015;
    static final double P_SPEED_SPIKE = 0.008;
    static final double P_DUPLICATE = 0.03;
    static final double P_LATE = 0.04;

    public record Generated(List<Ping> arrivalOrder,
                            Map<String, String> faultLabels,
                            Map<DriverDay, Double> groundTruthKm) {}

    private record Clean(Instant ts, double lat, double lon, double s, int leg) {}

    private record Trip(String driver, LocalDateTime startLocal, boolean forward, double kmh) {}

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "data");
        Generated g = generate(Config.standard(), SEED);
        PingCsv.write(dir.resolve("pings_SYNTHETIC.csv"), g.arrivalOrder());

        List<String> labels = new ArrayList<>();
        labels.add("data_label,ping_id,injected_fault");
        g.faultLabels().forEach((id, f) -> labels.add("SYNTHETIC," + id + "," + f));
        Files.write(dir.resolve("fault_labels_SYNTHETIC.csv"), labels);

        List<String> truth = new ArrayList<>();
        truth.add("data_label,driver_id,local_day_utc+9,true_km");
        g.groundTruthKm().forEach((k, v) -> truth.add(String.format("SYNTHETIC,%s,%s,%.4f", k.driver(), k.day(), v)));
        Files.write(dir.resolve("ground_truth_SYNTHETIC.csv"), truth);

        System.out.printf("Wrote %d pings (%d deliberately faulted) to %s%n",
                g.arrivalOrder().size(), g.faultLabels().size(), dir.toAbsolutePath());
    }

    public static Generated generate(Config cfg, long seed) {
        Random rnd = new Random(seed);
        Road road = new Road(cfg.road());

        String[] drivers = {"D01", "D02", "D03", "D04", "D05"};
        double[] baseKmh = {42, 55, 38, 61, 48};
        LocalDate day1 = LocalDate.of(2026, 11, 2), day2 = LocalDate.of(2026, 11, 3);

        List<Ping> all = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, List<Clean>> cleanByDriver = new LinkedHashMap<>();
        int[] counter = {0};

        for (int d = 0; d < drivers.length; d++) {
            List<Trip> trips = new ArrayList<>();
            double v = baseKmh[d];
            // Morning trip: 07:40 local = 22:40 UTC the PREVIOUS day -> exposes the UTC day cut
            trips.add(new Trip(drivers[d], day1.atTime(7, 40).plusMinutes(7L * d), true, v));
            trips.add(new Trip(drivers[d], day1.atTime(13, 10).plusMinutes(11L * d), false, v + 3));
            // Late-evening trip for D01/D03/D05 crosses LOCAL midnight while inside the zone
            trips.add(d % 2 == 0
                    ? new Trip(drivers[d], day1.atTime(23, 48).plusMinutes(d), true, v - 2)
                    : new Trip(drivers[d], day1.atTime(18, 30).plusMinutes(9L * d), true, v - 2));
            trips.add(new Trip(drivers[d], day2.atTime(8, 15).plusMinutes(5L * d), false, v + 1));
            trips.add(new Trip(drivers[d], day2.atTime(16, 5).plusMinutes(6L * d), true, v));

            List<Clean> cleanAll = new ArrayList<>();
            for (Trip t : trips) {
                List<Clean> clean = driveTrip(t, road, cfg, rnd);
                cleanAll.addAll(clean);
                injectFaults(t.driver(), clean, road, rnd, all, labels, counter);
            }
            cleanByDriver.put(drivers[d], cleanAll);
        }

        all.sort(Comparator.comparing(Ping::receivedAt).thenComparing(Ping::id));
        return new Generated(all, labels, groundTruth(cleanByDriver, cfg));
    }

    /** Drive the road end to end, one ping every 10 s, with small GPS jitter (~3 m). */
    private static List<Clean> driveTrip(Trip t, Road road, Config cfg, Random rnd) {
        List<Clean> out = new ArrayList<>();
        Instant ts = t.startLocal().toInstant(cfg.reportOffset());
        double travelled = 0;
        while (true) {
            double s = t.forward() ? travelled : road.length - travelled;
            double[] xy = road.xyAt(s);
            double[] ll = Geo.fromXY(xy[0] + rnd.nextGaussian() * 3, xy[1] + rnd.nextGaussian() * 3, road.refLat);
            out.add(new Clean(ts, ll[0], ll[1], s, road.legAt(s)));
            if (travelled >= road.length) break;
            double kmh = t.kmh() * Math.max(0.6, Math.min(1.3, 1 + rnd.nextGaussian() * 0.08));
            travelled = Math.min(road.length, travelled + kmh / 3.6 * PING_INTERVAL_S);
            ts = ts.plusSeconds(PING_INTERVAL_S);
        }
        return out;
    }

    private static void injectFaults(String driver, List<Clean> clean, Road road, Random rnd,
                                     List<Ping> all, Map<String, String> labels, int[] counter) {
        for (int i = 0; i < clean.size(); i++) {
            Clean c = clean.get(i);
            String id = String.format("P%05d", ++counter[0]);
            double lat = c.lat(), lon = c.lon();
            String fault = null;
            boolean eligible = i >= 2 && i <= clean.size() - 3;

            double r = rnd.nextDouble();
            if (eligible && r < P_OFF_ROAD && road.awayFromCorners(c.s(), 600)) {
                // Off-road noise: pushed 150-500 m sideways from the road
                double[] perp = road.perpendicular(c.leg());
                double mag = (150 + rnd.nextDouble() * 350) * (rnd.nextBoolean() ? 1 : -1);
                double[] xy = Geo.toXY(lat, lon, road.refLat);
                double[] ll = Geo.fromXY(xy[0] + perp[0] * mag, xy[1] + perp[1] * mag, road.refLat);
                lat = ll[0];
                lon = ll[1];
                fault = String.format("OFF_ROAD(%.0fm)", Math.abs(mag));
            } else if (eligible && r < P_OFF_ROAD + P_SPEED_SPIKE) {
                // Speed spike: ON the road but 0.8-2.5 km away from where the car really is
                double jump = 800 + rnd.nextDouble() * 1700;
                double s2 = c.s() + (rnd.nextBoolean() ? jump : -jump);
                if (s2 < 0 || s2 > road.length) s2 = c.s() - (s2 - c.s());
                if (s2 >= 0 && s2 <= road.length) {
                    double[] xy = road.xyAt(s2);
                    double[] ll = Geo.fromXY(xy[0], xy[1], road.refLat);
                    lat = ll[0];
                    lon = ll[1];
                    fault = String.format("SPEED_SPIKE(%.0fm jump)", jump);
                }
            }

            Instant received = c.ts().plusMillis(1000 + rnd.nextInt(2000));
            if (rnd.nextDouble() < P_LATE) {
                long lateS = 120 + rnd.nextInt(38 * 60);
                received = c.ts().plusSeconds(lateS);
                fault = (fault == null ? "" : fault + "+") + "LATE(" + lateS / 60 + "min)";
            }
            all.add(new Ping(id, driver, c.ts(), lat, lon, received));
            if (fault != null) labels.put(id, fault);

            if (fault == null && rnd.nextDouble() < P_DUPLICATE) {
                // Duplicate: same driver + same timestamp, re-sent 5-30 s later. Half exact, half re-fixed (8-25 m off).
                String dupId = String.format("P%05d", ++counter[0]);
                boolean exact = rnd.nextBoolean();
                double dLat = exact ? 0 : (8 + rnd.nextDouble() * 17) / 111_000.0 * (rnd.nextBoolean() ? 1 : -1);
                all.add(new Ping(dupId, driver, c.ts(), lat + dLat, lon,
                        received.plusSeconds(5 + rnd.nextInt(25))));
                labels.put(dupId, "DUPLICATE_OF_" + id + (exact ? "(exact)" : "(moved)"));
            }
        }
    }

    /** True in-zone km per driver per local day: fine sampling of each clean segment (200 steps). */
    static Map<DriverDay, Double> groundTruth(Map<String, List<Clean>> cleanByDriver, Config cfg) {
        Map<DriverDay, Double> km = new TreeMap<>();
        int steps = 200;
        for (Map.Entry<String, List<Clean>> e : cleanByDriver.entrySet()) {
            List<Clean> cs = e.getValue();
            for (int i = 1; i < cs.size(); i++) {
                Clean a = cs.get(i - 1), b = cs.get(i);
                double segM = Geo.haversineMetres(a.lat(), a.lon(), b.lat(), b.lon());
                long ms = Duration.between(a.ts(), b.ts()).toMillis();
                for (int k = 0; k < steps; k++) {
                    double f = (k + 0.5) / steps;
                    double lat = a.lat() + (b.lat() - a.lat()) * f;
                    double lon = a.lon() + (b.lon() - a.lon()) * f;
                    if (!cfg.inZone(lat, lon)) continue;
                    LocalDate day = a.ts().plusMillis((long) (ms * f)).atOffset(cfg.reportOffset()).toLocalDate();
                    km.merge(new DriverDay(e.getKey(), day), segM / steps / 1000.0, Double::sum);
                }
            }
        }
        return km;
    }

    /** The road polyline in local metres, so we can move along it by distance. */
    static final class Road {
        final double refLat;
        final List<double[]> xy = new ArrayList<>();
        final double[] cum;
        final double length;

        Road(List<double[]> latLon) {
            refLat = latLon.get(0)[0];
            for (double[] p : latLon) xy.add(Geo.toXY(p[0], p[1], refLat));
            cum = new double[xy.size()];
            for (int i = 1; i < xy.size(); i++) {
                cum[i] = cum[i - 1] + Math.hypot(xy.get(i)[0] - xy.get(i - 1)[0], xy.get(i)[1] - xy.get(i - 1)[1]);
            }
            length = cum[cum.length - 1];
        }

        int legAt(double s) {
            for (int i = 1; i < cum.length; i++) if (s <= cum[i]) return i - 1;
            return cum.length - 2;
        }

        double[] xyAt(double s) {
            int leg = legAt(s);
            double f = (s - cum[leg]) / (cum[leg + 1] - cum[leg]);
            double[] a = xy.get(leg), b = xy.get(leg + 1);
            return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
        }

        double[] perpendicular(int leg) {
            double[] a = xy.get(leg), b = xy.get(leg + 1);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            return new double[]{-(b[1] - a[1]) / len, (b[0] - a[0]) / len};
        }

        boolean awayFromCorners(double s, double metres) {
            for (double c : cum) if (Math.abs(s - c) < metres) return false;
            return true;
        }
    }
}
