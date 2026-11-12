package qa;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Small hand-checkable cases, one per rule. Used by the JUnit regression tests AND by RegressionMatrix.
 *
 * Test world: a straight road along the equator (lat 0), zone = lon 0.1..0.2.
 * One step = 0.002 deg of longitude every 10 s = 222.39 m (~80 km/h).
 * Expected km are written from the formula  R * radians(delta_lon), NOT computed by the code under test.
 *
 * Each scenario names the fix(es) it guards. "Teeth" check: the corrected engine with exactly those
 * fixes switched off must FAIL the scenario, otherwise the test is not really testing anything.
 */
public final class Scenarios {

    public record Scenario(String id, String rule, String whatItChecks, Config config, List<Ping> input,
                           Map<DriverDay, Double> expectedKm, Set<Fix> guards, Set<String> mustNotReject) {}

    public static final double TOLERANCE_KM = 0.001;   // 1 metre
    static final double STEP_DEG = 0.002;
    static final LocalDate DAY = LocalDate.of(2026, 11, 2);        // local (UTC+9) date
    static final Instant NOON_LOCAL = Instant.parse("2026-11-02T03:00:00Z");

    private Scenarios() {}

    public static Config testWorld() {
        return new Config(List.of(new double[]{0, 0}, new double[]{0, 1}),
                -0.01, 0.01, 0.1, 0.2, ZoneOffset.ofHours(9), 100, 150);
    }

    /** km for a span of longitude on the equator: the hand formula. */
    static double km(double deltaLonDeg) {
        return Geo.EARTH_RADIUS_M * Math.toRadians(deltaLonDeg) / 1000.0;
    }

    public static List<Scenario> all() {
        return List.of(noiseOffRoad(), noiseSpeedSpike(), duplicates(), latePings(),
                dayCutUtcPlus9(), zoneBoundary(), distanceFormula());
    }

    /** Clean drive: n+1 pings, one every 10 s, starting at lon0. */
    static List<Ping> drive(String driver, Instant start, double lon0, int segments) {
        List<Ping> out = new ArrayList<>();
        for (int i = 0; i <= segments; i++) {
            Instant ts = start.plusSeconds(10L * i);
            out.add(new Ping(driver + "-" + i, driver, ts, 0.0, lon0 + STEP_DEG * i, ts.plusSeconds(1)));
        }
        return out;
    }

    static Ping withPos(Ping p, double lat, double lon) {
        return new Ping(p.id(), p.driver(), p.ts(), lat, lon, p.receivedAt());
    }

    static Map<DriverDay, Double> expect(Object... driverDayKm) {
        Map<DriverDay, Double> m = new TreeMap<>();
        for (int i = 0; i < driverDayKm.length; i += 3) {
            m.put(new DriverDay((String) driverDayKm[i], (LocalDate) driverDayKm[i + 1]), (Double) driverDayKm[i + 2]);
        }
        return m;
    }

    // ---- 1. Noise: off road ------------------------------------------------------------------
    static Scenario noiseOffRoad() {
        List<Ping> ps = drive("A", NOON_LOCAL, 0.11, 10);
        // ping 5 jumps 333 m sideways. Implied speed ~120 km/h, so ONLY the off-road rule can catch it.
        ps.set(5, withPos(ps.get(5), 0.003, ps.get(5).lon()));
        return new Scenario("S1", "Noise: >100 m off road",
                "A ping 333 m off the road is ignored; distance equals the clean drive (10 steps)",
                testWorld(), ps, expect("A", DAY, km(10 * STEP_DEG)),
                EnumSet.of(Fix.DROP_OFF_ROAD), Set.of());
    }

    // ---- 2. Noise: impossible speed --------------------------------------------------------------
    static Scenario noiseSpeedSpike() {
        List<Ping> ps = drive("A", NOON_LOCAL, 0.11, 10);
        // ping 5 teleports 2.2 km ahead ON the road (~800 km/h). Ping 6 is genuine and must be kept:
        // a naive check against the previous RAW ping would reject it too.
        ps.set(5, withPos(ps.get(5), 0.0, 0.18));
        return new Scenario("S2", "Noise: >150 km/h",
                "A 2.2 km on-road jump is ignored, and the good ping right after it (A-6) is kept",
                testWorld(), ps, expect("A", DAY, km(10 * STEP_DEG)),
                EnumSet.of(Fix.DROP_IMPOSSIBLE_SPEED), Set.of("A-6"));
    }

    // ---- 3. Duplicates ---------------------------------------------------------------------------
    static Scenario duplicates() {
        List<Ping> clean = drive("A", NOON_LOCAL, 0.11, 10);
        List<Ping> ps = new ArrayList<>();
        for (Ping p : clean) {
            ps.add(p);
            double dLat = Integer.parseInt(p.id().substring(2)) % 2 == 1 ? 0.0002 : 0.0;   // odd ones re-fixed 22 m away
            ps.add(new Ping(p.id() + "-dup", p.driver(), p.ts(), p.lat() + dLat, p.lon(), p.receivedAt().plusSeconds(5)));
        }
        // Note: the speed filter ALSO rejects a moved duplicate (22 m in 0 s), so this scenario guards
        // both. Removing only DEDUPE still passes - documented in directive.md as a finding.
        return new Scenario("S3", "Duplicates count once",
                "Every ping sent twice (half exact, half 22 m off): distance equals the clean drive",
                testWorld(), ps, expect("A", DAY, km(10 * STEP_DEG)),
                EnumSet.of(Fix.DEDUPE, Fix.DROP_IMPOSSIBLE_SPEED), Set.of());
    }

    // ---- 4. Late / out of order ------------------------------------------------------------------
    static Scenario latePings() {
        List<Ping> clean = drive("A", NOON_LOCAL, 0.11, 10);
        List<Ping> ps = new ArrayList<>();
        List<Ping> late = new ArrayList<>();
        for (Ping p : clean) {
            int i = Integer.parseInt(p.id().substring(2));
            if (i >= 3 && i <= 5) late.add(new Ping(p.id(), p.driver(), p.ts(), p.lat(), p.lon(), p.ts().plusSeconds(600)));
            else ps.add(p);
        }
        ps.addAll(late);   // pings 3,4,5 arrive 10 minutes late, after ping 10
        return new Scenario("S4", "Late pings placed by own timestamp",
                "Pings 3-5 arrive 10 min late (after ping 10): distance equals the clean drive",
                testWorld(), ps, expect("A", DAY, km(10 * STEP_DEG)),
                EnumSet.of(Fix.SORT_BY_TIMESTAMP), Set.of());
    }

    // ---- 5. Day cut at 00:00 UTC+9 ----------------------------------------------------------------
    static Scenario dayCutUtcPlus9() {
        // Driver A crosses local midnight (15:00 UTC). Midnight falls exactly halfway through one segment:
        // 5.5 steps before midnight, 6.5 after.
        List<Ping> ps = new ArrayList<>(drive("A", Instant.parse("2026-11-02T14:59:05Z"), 0.11, 12));
        // Driver B drives at 23:30 UTC = 08:30 local on the NEXT day. UTC date says Nov 2, local says Nov 3.
        ps.addAll(drive("B", Instant.parse("2026-11-02T23:30:00Z"), 0.11, 5));
        LocalDate next = DAY.plusDays(1);
        return new Scenario("S5", "Day cut at 00:00 UTC+9",
                "Trip across local midnight is split 5.5/6.5 steps; an 08:30-local trip lands on the local date",
                testWorld(), ps,
                expect("A", DAY, km(5.5 * STEP_DEG), "A", next, km(6.5 * STEP_DEG), "B", next, km(5 * STEP_DEG)),
                EnumSet.of(Fix.LOCAL_DAY_CUT), Set.of());
    }

    // ---- 6. Zone boundary ------------------------------------------------------------------------
    static Scenario zoneBoundary() {
        // A enters the zone (edge at lon 0.1) mid-segment, B leaves it (edge at 0.2) mid-segment.
        List<Ping> ps = new ArrayList<>(drive("A", NOON_LOCAL, 0.091, 10));   // 0.091 -> 0.111, inside 0.100..0.111
        ps.addAll(drive("B", NOON_LOCAL, 0.191, 10));                           // 0.191 -> 0.211, inside 0.191..0.200
        return new Scenario("S6", "Distance only inside the zone",
                "Segments crossing the zone edge count only their inside part (0.011 and 0.009 deg)",
                testWorld(), ps, expect("A", DAY, km(0.011), "B", DAY, km(0.009)),
                EnumSet.of(Fix.CLIP_AT_ZONE_BOUNDARY), Set.of());
    }

    // ---- 7. Distance formula (extra, found by the tool) -------------------------------------------
    static Scenario distanceFormula() {
        double lat = 37.5;
        Config c = new Config(List.of(new double[]{lat, 127.0}, new double[]{lat, 128.0}),
                37.0, 38.0, 127.0, 128.0, ZoneOffset.ofHours(9), 100, 150);
        List<Ping> ps = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            Instant ts = NOON_LOCAL.plusSeconds(10L * i);
            ps.add(new Ping("A-" + i, "A", ts, lat, 127.1 + STEP_DEG * i, ts.plusSeconds(1)));
        }
        // East-west at 37.5N: one degree of longitude = R * cos(37.5) * pi/180
        double expected = Geo.EARTH_RADIUS_M * Math.cos(Math.toRadians(lat)) * Math.toRadians(10 * STEP_DEG) / 1000.0;
        return new Scenario("S7", "Correct distance formula (extra)",
                "1.76 km east-west at latitude 37.5 measures 1.76 km, not 2.23 km (flat degrees)",
                c, ps, expect("A", DAY, expected),
                EnumSet.of(Fix.GREAT_CIRCLE_DISTANCE), Set.of());
    }

    // ---- evaluation ----------------------------------------------------------------------------
    public record Outcome(boolean pass, String detail) {}

    public static Outcome evaluate(Scenario s, DistanceCalculator calc) {
        Map<DriverDay, Double> got = calc.kmPerDriverDay(s.input());
        Set<DriverDay> keys = new java.util.TreeSet<>(s.expectedKm().keySet());
        keys.addAll(got.keySet());
        StringBuilder why = new StringBuilder();
        for (DriverDay k : keys) {
            double e = s.expectedKm().getOrDefault(k, 0.0), g = got.getOrDefault(k, 0.0);
            if (Math.abs(e - g) > TOLERANCE_KM) {
                why.append(String.format("%s expected %.4f km got %.4f; ", k, e, g));
            }
        }
        if (calc instanceof DistanceEngine eng) {
            for (DistanceEngine.Rejection r : eng.rejections()) {
                if (s.mustNotReject().contains(r.ping().id())) {
                    why.append("good ping ").append(r.ping().id()).append(" was rejected; ");
                }
            }
        }
        return new Outcome(why.length() == 0, why.length() == 0 ? "ok" : why.toString().trim());
    }
}
