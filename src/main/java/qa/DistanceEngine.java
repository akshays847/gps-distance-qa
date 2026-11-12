package qa;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * v2 - THE CORRECTED REFERENCE IMPLEMENTATION.
 *
 * With all fixes enabled this is the fixed calculation.
 * With NO fixes enabled it reproduces v1 exactly (checked by a test),
 * so switching fixes on one at a time shows how many km each bug is worth.
 */
public class DistanceEngine implements DistanceCalculator {

    public enum Reason { DUPLICATE, OFF_ROAD, IMPOSSIBLE_SPEED }

    public record Rejection(Ping ping, Reason reason, String detail) {}

    private final Config config;
    private final Set<Fix> fixes;
    private final List<Rejection> rejections = new ArrayList<>();

    public DistanceEngine(Config config, Set<Fix> fixes) {
        this.config = config;
        this.fixes = fixes.isEmpty() ? EnumSet.noneOf(Fix.class) : EnumSet.copyOf(fixes);
    }

    public static DistanceEngine fixed(Config config) {
        return new DistanceEngine(config, EnumSet.allOf(Fix.class));
    }

    /** All fixes except the ones given - used to prove each regression test has teeth. */
    public static DistanceEngine fixedWithout(Config config, Set<Fix> removed) {
        EnumSet<Fix> s = EnumSet.allOf(Fix.class);
        s.removeAll(removed);
        return new DistanceEngine(config, s);
    }

    @Override
    public String name() {
        return fixes.size() == Fix.values().length ? "v2 fixed" : "engine " + fixes;
    }

    public List<Rejection> rejections() {
        return rejections;
    }

    @Override
    public Map<DriverDay, Double> kmPerDriverDay(List<Ping> pingsInArrivalOrder) {
        rejections.clear();
        Map<DriverDay, Double> km = new TreeMap<>();

        Map<String, List<Ping>> byDriver = new LinkedHashMap<>();
        for (Ping p : pingsInArrivalOrder) {
            byDriver.computeIfAbsent(p.driver(), k -> new ArrayList<>()).add(p);
        }

        for (List<Ping> raw : byDriver.values()) {
            List<Ping> ps = new ArrayList<>(raw);

            // 1. Duplicates: same driver + same timestamp counts once. Keep the first one received.
            if (fixes.contains(Fix.DEDUPE)) {
                Set<Instant> seen = new HashSet<>();
                List<Ping> kept = new ArrayList<>();
                for (Ping p : ps) {
                    if (seen.add(p.ts())) kept.add(p);
                    else rejections.add(new Rejection(p, Reason.DUPLICATE, "same driver+ts already received"));
                }
                ps = kept;
            }

            // 2. Late / out-of-order: place every ping by its own timestamp (stable sort).
            if (fixes.contains(Fix.SORT_BY_TIMESTAMP)) {
                ps.sort(Comparator.comparing(Ping::ts));
            }

            // 3. Noise: more than 100 m off the road path.
            if (fixes.contains(Fix.DROP_OFF_ROAD)) {
                List<Ping> kept = new ArrayList<>();
                for (Ping p : ps) {
                    double off = config.distanceToRoadMetres(p.lat(), p.lon());
                    if (off > config.maxOffRoadMetres()) {
                        rejections.add(new Rejection(p, Reason.OFF_ROAD, String.format("%.0f m off road", off)));
                    } else {
                        kept.add(p);
                    }
                }
                ps = kept;
            }

            // 4. Noise: implied speed above 150 km/h. Compare with the LAST VALID ping,
            //    otherwise one spike also throws away the good ping right after it.
            if (fixes.contains(Fix.DROP_IMPOSSIBLE_SPEED)) {
                List<Ping> kept = new ArrayList<>();
                Ping lastValid = null;
                for (Ping p : ps) {
                    if (lastValid != null) {
                        double seconds = Duration.between(lastValid.ts(), p.ts()).toMillis() / 1000.0;
                        double metres = Geo.haversineMetres(lastValid.lat(), lastValid.lon(), p.lat(), p.lon());
                        double kmh = metres / seconds * 3.6;   // seconds==0 and metres>0 -> Infinity -> rejected
                        if (kmh > config.maxSpeedKmh()) {
                            rejections.add(new Rejection(p, Reason.IMPOSSIBLE_SPEED,
                                    String.format("%.0f km/h from %s", kmh, lastValid.id())));
                            continue;
                        }
                    }
                    kept.add(p);
                    lastValid = p;
                }
                ps = kept;
            }

            // 5. Add up the segments between consecutive kept pings.
            for (int i = 1; i < ps.size(); i++) {
                addSegment(km, ps.get(i - 1), ps.get(i));
            }
        }
        return km;
    }

    private void addSegment(Map<DriverDay, Double> km, Ping a, Ping b) {
        double metres = fixes.contains(Fix.GREAT_CIRCLE_DISTANCE)
                ? Geo.haversineMetres(a.lat(), a.lon(), b.lat(), b.lon())
                : Geo.flatDegreesMetres(a.lat(), a.lon(), b.lat(), b.lon());

        // Which part of the segment (0..1) is inside the zone?
        double t0, t1;
        if (fixes.contains(Fix.CLIP_AT_ZONE_BOUNDARY)) {
            double[] clip = Geo.clipToZone(a.lat(), a.lon(), b.lat(), b.lon(), config);
            if (clip == null) return;
            t0 = clip[0];
            t1 = clip[1];
        } else {
            if (!config.inZone(a.lat(), a.lon())) return;   // legacy: whole segment if start is inside
            t0 = 0;
            t1 = 1;
        }

        if (!fixes.contains(Fix.LOCAL_DAY_CUT)) {
            LocalDate day = a.ts().atOffset(ZoneOffset.UTC).toLocalDate();
            km.merge(new DriverDay(a.driver(), day), metres * (t1 - t0) / 1000.0, Double::sum);
            return;
        }

        // Split the in-zone part at every local (UTC+9) midnight between a and b.
        // Position along the segment is assumed to move linearly with time.
        ZoneOffset off = config.reportOffset();
        long totalMs = Duration.between(a.ts(), b.ts()).toMillis();
        LocalDate dayA = a.ts().atOffset(off).toLocalDate();
        if (totalMs <= 0) {
            km.merge(new DriverDay(a.driver(), dayA), metres * (t1 - t0) / 1000.0, Double::sum);
            return;
        }
        double start = 0;
        LocalDate day = dayA;
        while (true) {
            Instant nextMidnight = day.plusDays(1).atStartOfDay().toInstant(off);
            double end = Math.min(1.0, Duration.between(a.ts(), nextMidnight).toMillis() / (double) totalMs);
            double overlap = Math.max(0, Math.min(end, t1) - Math.max(start, t0));
            if (overlap > 0) {
                km.merge(new DriverDay(a.driver(), day), metres * overlap / 1000.0, Double::sum);
            }
            if (end >= 1.0) break;
            start = end;
            day = day.plusDays(1);
        }
    }
}
