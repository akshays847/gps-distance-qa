package qa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * THE QA TOOL.
 *
 *   java -cp target/classes qa.QaChecker [pings.csv] [fault_labels.csv] [ground_truth.csv] [outDir]
 *
 * 1. Data checks   - looks at the raw feed and lists what is wrong, with ping ids as evidence
 * 2. Root cause    - switches each fix on one at a time and measures how many km each bug is worth
 * 3. Before/after  - v1 vs v2 vs true km, per driver per day
 * 4. Answer key    - did v2 reject exactly the pings that were faulted (synthetic data only)
 * 5. Regression    - runs every regression scenario against v1, v2 and v2-minus-that-fix
 * Writes reports/qa_report.html plus CSVs. Exit code 1 if v2 is off the true km by more than tolerance.
 */
public class QaChecker {

    public record Check(String id, String name, int count, String finding, List<String> evidence) {}

    public record Step(String fixLabel, double totalKm, double deltaKm, double movedBetweenDaysKm) {}

    static final double TRUTH_TOLERANCE_KM = 0.05;   // 50 m per driver-day (see directive.md)

    public static void main(String[] args) throws IOException {
        Path pingsFile = Path.of(arg(args, 0, "data/pings_SYNTHETIC.csv"));
        Path labelsFile = Path.of(arg(args, 1, "data/fault_labels_SYNTHETIC.csv"));
        Path truthFile = Path.of(arg(args, 2, "data/ground_truth_SYNTHETIC.csv"));
        Path out = Path.of(arg(args, 3, "reports"));
        Files.createDirectories(out);

        Config cfg = Config.standard();
        List<Ping> pings = PingCsv.read(pingsFile);
        Map<String, String> labels = Files.exists(labelsFile) ? readLabels(labelsFile) : Map.of();
        Map<DriverDay, Double> truth = Files.exists(truthFile) ? readTruth(truthFile) : Map.of();

        // 1. Data checks
        List<Check> checks = dataChecks(pings, cfg);

        // 2. Calculations
        Map<DriverDay, Double> v1 = new LegacyCalculator(cfg).kmPerDriverDay(pings);
        DistanceEngine engine = DistanceEngine.fixed(cfg);
        Map<DriverDay, Double> v2 = engine.kmPerDriverDay(pings);
        List<DistanceEngine.Rejection> rejected = new ArrayList<>(engine.rejections());

        // Sanity: the engine with no fixes must reproduce v1 exactly, or the root-cause numbers mean nothing
        Map<DriverDay, Double> noFix = new DistanceEngine(cfg, EnumSet.noneOf(Fix.class)).kmPerDriverDay(pings);
        boolean engineMatchesLegacy = maxDiff(noFix, v1) < 1e-9;

        // 3. Root cause waterfall
        List<Step> steps = waterfall(pings, cfg);

        // 4. Answer key
        List<String[]> detection = labels.isEmpty() ? List.of() : detection(labels, rejected);

        // 5. Regression matrix
        List<String[]> matrix = regressionMatrix();

        // Verdict
        double worst = truth.isEmpty() ? 0 : maxDiff(v2, truth);
        boolean ok = truth.isEmpty() || worst <= TRUTH_TOLERANCE_KM;

        printConsole(pings, checks, v1, v2, truth, steps, detection, matrix, engineMatchesLegacy, worst, ok);
        writeCsvs(out, checks, v1, v2, truth, steps);
        Files.writeString(out.resolve("qa_report.html"),
                html(pings, cfg, checks, v1, v2, truth, steps, detection, matrix, rejected, engineMatchesLegacy, worst, ok));
        Files.writeString(out.resolve("regression_matrix.md"), matrixMarkdown(matrix));
        System.out.println("\nReport: " + out.resolve("qa_report.html").toAbsolutePath());
        if (!ok) System.exit(1);
    }

    // =========================================================================================
    // 1. DATA CHECKS
    // =========================================================================================
    static List<Check> dataChecks(List<Ping> pings, Config cfg) {
        List<Check> checks = new ArrayList<>();
        Map<String, List<Ping>> byDriver = groupByDriver(pings);

        // C1 duplicates
        {
            int extra = 0, moved = 0;
            List<String> ev = new ArrayList<>();
            for (List<Ping> ps : byDriver.values()) {
                Map<Instant, Ping> first = new HashMap<>();
                for (Ping p : ps) {
                    Ping f = first.putIfAbsent(p.ts(), p);
                    if (f != null) {
                        extra++;
                        double m = Geo.haversineMetres(f.lat(), f.lon(), p.lat(), p.lon());
                        if (m > 0.5) moved++;
                        if (ev.size() < 5) ev.add(String.format("%s: %s and %s share ts %s (%.0f m apart)",
                                p.driver(), f.id(), p.id(), p.ts(), m));
                    }
                }
            }
            checks.add(new Check("C1", "Duplicate pings (same driver + timestamp)", extra,
                    String.format("%d extra rows (%d exact copies, %d with different coordinates). v1 counts them all.",
                            extra, extra - moved, moved), ev));
        }

        // C2 out of order
        {
            int count = 0;
            long maxLateS = 0;
            double legacyExtraM = 0;
            List<String> ev = new ArrayList<>();
            for (List<Ping> ps : byDriver.values()) {
                Instant maxTs = Instant.MIN;
                Ping prev = null, newest = null;
                Set<Instant> seenTs = new HashSet<>();
                for (Ping p : ps) {
                    if (!seenTs.add(p.ts())) { prev = p; continue; }   // duplicate: reported by C1, not a late ping
                    if (p.ts().isBefore(maxTs)) {
                        count++;
                        long late = Duration.between(p.ts(), p.receivedAt()).toSeconds();
                        maxLateS = Math.max(maxLateS, late);
                        double back = Geo.flatDegreesMetres(prev.lat(), prev.lon(), p.lat(), p.lon());
                        legacyExtraM += back;
                        if (ev.size() < 5) ev.add(String.format("%s %s ts %s arrived %d min late, after %s (ts %s); v1 draws a %.2f km line back to it",
                                p.driver(), p.id(), p.ts(), late / 60, newest.id(), newest.ts(), back / 1000));
                    } else {
                        maxTs = p.ts();
                        newest = p;
                    }
                    prev = p;
                }
            }
            checks.add(new Check("C2", "Late / out-of-order pings", count,
                    String.format("%d pings arrived after a newer ping (latest %d min late). In arrival order v1 jumps back and forth: ~%.1f km of jump-back lines (before zone filter).",
                            count, maxLateS / 60, legacyExtraM / 1000), ev));
        }

        // C3 off road
        {
            int count = 0;
            double max = 0;
            List<String> ev = new ArrayList<>();
            for (Ping p : pings) {
                double d = cfg.distanceToRoadMetres(p.lat(), p.lon());
                if (d > cfg.maxOffRoadMetres()) {
                    count++;
                    max = Math.max(max, d);
                    if (ev.size() < 5) ev.add(String.format("%s %s is %.0f m from the road", p.driver(), p.id(), d));
                }
            }
            checks.add(new Check("C3", "GPS noise: more than 100 m off the road", count,
                    String.format("%d pings off the road (worst %.0f m). v1 uses them.", count, max), ev));
        }

        // C4 impossible speed (on deduped, time-sorted, on-road pings)
        {
            int vsLastValid = 0, vsPrevRaw = 0;
            List<String> ev = new ArrayList<>();
            for (List<Ping> ps : byDriver.values()) {
                List<Ping> clean = cleanedOrder(ps, cfg);
                Ping lastValid = null, prevRaw = null;
                for (Ping p : clean) {
                    if (prevRaw != null && kmh(prevRaw, p) > cfg.maxSpeedKmh()) vsPrevRaw++;
                    boolean bad = lastValid != null && kmh(lastValid, p) > cfg.maxSpeedKmh();
                    if (bad) {
                        vsLastValid++;
                        if (ev.size() < 5) ev.add(String.format("%s %s implies %.0f km/h from %s", p.driver(), p.id(), kmh(lastValid, p), lastValid.id()));
                    } else {
                        lastValid = p;
                    }
                    prevRaw = p;
                }
            }
            checks.add(new Check("C4", "GPS noise: implied speed above 150 km/h", vsLastValid,
                    String.format("%d pings imply >150 km/h vs the last valid ping. A naive check vs the previous ping flags %d - it would also throw away %d good pings.",
                            vsLastValid, vsPrevRaw, vsPrevRaw - vsLastValid), ev));
        }

        // C5 day cut
        {
            int count = 0, midnightSegs = 0;
            List<String> ev = new ArrayList<>();
            for (Ping p : pings) {
                LocalDate utc = p.ts().atOffset(ZoneOffset.UTC).toLocalDate();
                LocalDate local = p.ts().atOffset(cfg.reportOffset()).toLocalDate();
                if (!utc.equals(local)) {
                    count++;
                    if (ev.size() < 3) ev.add(String.format("%s %s ts %s: UTC date %s, report date (UTC+9) %s", p.driver(), p.id(), p.ts(), utc, local));
                }
            }
            for (List<Ping> ps : byDriver.values()) {
                List<Ping> clean = fullyCleaned(ps, cfg);
                for (int i = 1; i < clean.size(); i++) {
                    Ping a = clean.get(i - 1), b = clean.get(i);
                    if (!a.ts().atOffset(cfg.reportOffset()).toLocalDate().equals(b.ts().atOffset(cfg.reportOffset()).toLocalDate())
                            && Geo.clipToZone(a.lat(), a.lon(), b.lat(), b.lon(), cfg) != null) {
                        midnightSegs++;
                        if (ev.size() < 5) ev.add(String.format("%s segment %s -> %s crosses local midnight inside the zone", a.driver(), a.id(), b.id()));
                    }
                }
            }
            checks.add(new Check("C5", "Day cut: UTC date vs report date (00:00 UTC+9)", count,
                    String.format("%d pings fall on a different date in UTC than in UTC+9 (everything 00:00-09:00 local). %d in-zone segments cross local midnight. v1 books by UTC date.",
                            count, midnightSegs), ev));
        }

        // C6 zone boundary + C7 distance formula (on cleaned data)
        {
            int crossings = 0;
            double startRuleKm = 0, clippedKm = 0, flatKm = 0, haversineKm = 0;
            List<String> ev = new ArrayList<>();
            for (List<Ping> ps : byDriver.values()) {
                List<Ping> clean = fullyCleaned(ps, cfg);
                for (int i = 1; i < clean.size(); i++) {
                    Ping a = clean.get(i - 1), b = clean.get(i);
                    double m = Geo.haversineMetres(a.lat(), a.lon(), b.lat(), b.lon());
                    boolean ia = cfg.inZone(a.lat(), a.lon()), ib = cfg.inZone(b.lat(), b.lon());
                    double[] clip = Geo.clipToZone(a.lat(), a.lon(), b.lat(), b.lon(), cfg);
                    if (ia) startRuleKm += m / 1000;
                    if (clip != null) clippedKm += m * (clip[1] - clip[0]) / 1000;
                    if (ia != ib) {
                        crossings++;
                        if (ev.size() < 4) ev.add(String.format("%s %s -> %s crosses the zone edge; inside part = %.0f%% of %.0f m, v1 counts %s",
                                a.driver(), a.id(), b.id(), clip == null ? 0 : (clip[1] - clip[0]) * 100, m, ia ? "100%" : "0%"));
                    }
                    if (clip != null) {
                        haversineKm += m / 1000;
                        flatKm += Geo.flatDegreesMetres(a.lat(), a.lon(), b.lat(), b.lon()) / 1000;
                    }
                }
            }
            checks.add(new Check("C6", "Zone boundary: segments crossing the zone edge", crossings,
                    String.format("%d segments cross the zone edge. v1 counts a whole segment if its start is inside: %.3f km vs %.3f km clipped (%+.3f km).",
                            crossings, startRuleKm, clippedKm, startRuleKm - clippedKm), ev));
            checks.add(new Check("C7", "Distance formula: flat degrees vs great-circle", 1,
                    String.format("On the same in-zone segments v1's flat-degree formula gives %.2f km vs %.2f km haversine (%+.1f%%). Longitude degrees are treated as 111 km; at 37.5N they are 88 km.",
                            flatKm, haversineKm, (flatKm / haversineKm - 1) * 100),
                    List.of("found by the tool, not in the brief's list of faults")));
        }
        return checks;
    }

    /** Dedupe (first received), sort by ts, drop off-road. Used by checks that need a clean order. */
    static List<Ping> cleanedOrder(List<Ping> ps, Config cfg) {
        Set<Instant> seen = new HashSet<>();
        List<Ping> out = new ArrayList<>();
        for (Ping p : ps) if (seen.add(p.ts())) out.add(p);
        out.sort(Comparator.comparing(Ping::ts));
        out.removeIf(p -> cfg.distanceToRoadMetres(p.lat(), p.lon()) > cfg.maxOffRoadMetres());
        return out;
    }

    /** cleanedOrder plus the speed filter (vs last valid ping): the fully cleaned track. */
    static List<Ping> fullyCleaned(List<Ping> ps, Config cfg) {
        List<Ping> out = new ArrayList<>();
        Ping lastValid = null;
        for (Ping p : cleanedOrder(ps, cfg)) {
            if (lastValid != null && kmh(lastValid, p) > cfg.maxSpeedKmh()) continue;
            out.add(p);
            lastValid = p;
        }
        return out;
    }

    static double kmh(Ping a, Ping b) {
        double s = Duration.between(a.ts(), b.ts()).toMillis() / 1000.0;
        return Geo.haversineMetres(a.lat(), a.lon(), b.lat(), b.lon()) / s * 3.6;
    }

    // =========================================================================================
    // 2. ROOT CAUSE: switch fixes on one by one
    // =========================================================================================
    static List<Step> waterfall(List<Ping> pings, Config cfg) {
        List<Step> steps = new ArrayList<>();
        EnumSet<Fix> on = EnumSet.noneOf(Fix.class);
        Map<DriverDay, Double> prev = new DistanceEngine(cfg, on).kmPerDriverDay(pings);
        steps.add(new Step("v1 (no fixes)", total(prev), 0, 0));
        for (Fix f : Fix.values()) {
            on.add(f);
            Map<DriverDay, Double> cur = new DistanceEngine(cfg, on).kmPerDriverDay(pings);
            // km that stayed billed for the same driver but moved to a different day, summed over drivers
            Map<String, double[]> perDriver = new TreeMap<>();   // {sum |day change|, net change}
            for (DriverDay k : allKeys(prev, cur)) {
                double d = cur.getOrDefault(k, 0.0) - prev.getOrDefault(k, 0.0);
                double[] acc = perDriver.computeIfAbsent(k.driver(), x -> new double[2]);
                acc[0] += Math.abs(d);
                acc[1] += d;
            }
            double moved = 0;
            for (double[] acc : perDriver.values()) moved += Math.max(0, (acc[0] - Math.abs(acc[1])) / 2);
            double delta = total(cur) - total(prev);
            steps.add(new Step("+ " + f.label, total(cur), delta, moved));
            prev = cur;
        }
        return steps;
    }

    // =========================================================================================
    // 4. ANSWER KEY
    // =========================================================================================
    static List<String[]> detection(Map<String, String> labels, List<DistanceEngine.Rejection> rejected) {
        Map<String, DistanceEngine.Reason> rej = new HashMap<>();
        for (DistanceEngine.Rejection r : rejected) rej.put(r.ping().id(), r.reason());
        String[][] types = {{"OFF_ROAD", "OFF_ROAD"}, {"SPEED_SPIKE", "IMPOSSIBLE_SPEED"}, {"DUPLICATE", "DUPLICATE"}};
        List<String[]> rows = new ArrayList<>();
        Set<String> expectedRejected = new HashSet<>();
        for (String[] t : types) {
            int injected = 0, caught = 0;
            List<String> missed = new ArrayList<>();
            for (Map.Entry<String, String> e : labels.entrySet()) {
                if (!e.getValue().startsWith(t[0])) continue;
                injected++;
                expectedRejected.add(e.getKey());
                if (rej.get(e.getKey()) == DistanceEngine.Reason.valueOf(t[1])) caught++;
                else missed.add(e.getKey() + (rej.containsKey(e.getKey()) ? "(as " + rej.get(e.getKey()) + ")" : ""));
            }
            rows.add(new String[]{t[0], String.valueOf(injected), String.valueOf(caught), missed.isEmpty() ? "-" : String.join(" ", missed)});
        }
        int late = 0, lateKept = 0;
        for (Map.Entry<String, String> e : labels.entrySet()) {
            if (e.getValue().startsWith("LATE")) {
                late++;
                if (!rej.containsKey(e.getKey())) lateKept++;
            }
        }
        rows.add(new String[]{"LATE (should be KEPT, re-ordered)", String.valueOf(late), String.valueOf(lateKept), "-"});
        List<String> falsePos = new ArrayList<>();
        for (String id : rej.keySet()) if (!expectedRejected.contains(id)) falsePos.add(id + "(" + rej.get(id) + ")");
        falsePos.sort(null);
        rows.add(new String[]{"Rejected but NOT faulted (false positives)", "-", String.valueOf(falsePos.size()),
                falsePos.isEmpty() ? "-" : String.join(" ", falsePos)});
        return rows;
    }

    // =========================================================================================
    // 5. REGRESSION MATRIX
    // =========================================================================================
    static List<String[]> regressionMatrix() {
        List<String[]> rows = new ArrayList<>();
        for (Scenarios.Scenario s : Scenarios.all()) {
            Scenarios.Outcome v1 = Scenarios.evaluate(s, new LegacyCalculator(s.config()));
            Scenarios.Outcome v2 = Scenarios.evaluate(s, DistanceEngine.fixed(s.config()));
            Scenarios.Outcome minus = Scenarios.evaluate(s, DistanceEngine.fixedWithout(s.config(), s.guards()));
            rows.add(new String[]{s.id(), s.rule(), s.whatItChecks(),
                    v1.pass() ? "PASS" : "FAIL", v2.pass() ? "PASS" : "FAIL", minus.pass() ? "PASS" : "FAIL",
                    s.guards().toString(), v1.detail(), v2.detail()});
        }
        return rows;
    }

    static String matrixMarkdown(List<String[]> m) {
        StringBuilder sb = new StringBuilder("# Regression matrix (generated by QaChecker)\n\n");
        sb.append("Expected: v1 FAIL, v2 PASS, v2-without-this-fix FAIL.\n\n");
        sb.append("| ID | Rule | v1 (old) | v2 (fixed) | v2 minus fix | Fix guarded |\n|---|---|---|---|---|---|\n");
        for (String[] r : m) sb.append(String.format("| %s | %s | %s | %s | %s | %s |%n", r[0], r[1], r[3], r[4], r[5], r[6]));
        return sb.toString();
    }

    // =========================================================================================
    // OUTPUT
    // =========================================================================================
    static void printConsole(List<Ping> pings, List<Check> checks, Map<DriverDay, Double> v1, Map<DriverDay, Double> v2,
                             Map<DriverDay, Double> truth, List<Step> steps, List<String[]> detection,
                             List<String[]> matrix, boolean engineOk, double worst, boolean ok) {
        System.out.printf("%nGPS DISTANCE QA  -  %d pings  (SYNTHETIC DATA)%n", pings.size());
        System.out.println("=".repeat(100));
        System.out.println("1. DATA CHECKS");
        for (Check c : checks) {
            System.out.printf("  [%s] %-50s %5d%n       %s%n", c.id(), c.name(), c.count(), c.finding());
        }
        System.out.println("\n2. ROOT CAUSE  (fixes switched on one at a time; engine-with-no-fixes == v1: " + (engineOk ? "YES" : "NO!") + ")");
        System.out.printf("  %-55s %10s %10s %14s%n", "Step", "Total km", "Change", "Moved day");
        for (Step s : steps) System.out.printf("  %-55s %10.3f %+10.3f %14.3f%n", s.fixLabel(), s.totalKm(), s.deltaKm(), s.movedBetweenDaysKm());

        System.out.println("\n3. BEFORE / AFTER per driver per day (km)");
        System.out.printf("  %-18s %10s %10s %10s %12s%n", "Driver / day", "v1 old", "v2 fixed", "true", "v2 - true");
        for (DriverDay k : allKeys(v1, v2, truth)) {
            System.out.printf("  %-18s %10.3f %10.3f %10s %12s%n", k, v1.getOrDefault(k, 0.0), v2.getOrDefault(k, 0.0),
                    truth.isEmpty() ? "-" : String.format("%.3f", truth.getOrDefault(k, 0.0)),
                    truth.isEmpty() ? "-" : String.format("%+.3f", v2.getOrDefault(k, 0.0) - truth.getOrDefault(k, 0.0)));
        }
        System.out.printf("  %-18s %10.3f %10.3f %10s%n", "TOTAL", total(v1), total(v2), truth.isEmpty() ? "-" : String.format("%.3f", total(truth)));

        if (!detection.isEmpty()) {
            System.out.println("\n4. ANSWER KEY  (fault type / injected / handled correctly / missed)");
            for (String[] r : detection) System.out.printf("  %-45s %6s %6s   %s%n", r[0], r[1], r[2], r[3]);
        }
        System.out.println("\n5. REGRESSION MATRIX  (expected: v1 FAIL, v2 PASS, v2-minus-fix FAIL)");
        for (String[] r : matrix) System.out.printf("  %s %-38s v1:%s  v2:%s  minus-fix:%s%n", r[0], r[1], r[3], r[4], r[5]);

        System.out.println("\nVERDICT: " + (truth.isEmpty() ? "no ground truth file" :
                String.format("v2 worst driver-day error vs true km = %.4f km (tolerance %.3f) -> %s", worst, TRUTH_TOLERANCE_KM, ok ? "PASS" : "FAIL")));
    }

    static void writeCsvs(Path out, List<Check> checks, Map<DriverDay, Double> v1, Map<DriverDay, Double> v2,
                          Map<DriverDay, Double> truth, List<Step> steps) throws IOException {
        List<String> ba = new ArrayList<>();
        ba.add("data_label,driver_id,day,v1_km,v2_km,true_km,v2_minus_true_km");
        for (DriverDay k : allKeys(v1, v2, truth)) {
            ba.add(String.format("SYNTHETIC,%s,%s,%.4f,%.4f,%s,%s", k.driver(), k.day(), v1.getOrDefault(k, 0.0), v2.getOrDefault(k, 0.0),
                    truth.isEmpty() ? "" : String.format("%.4f", truth.getOrDefault(k, 0.0)),
                    truth.isEmpty() ? "" : String.format("%.4f", v2.getOrDefault(k, 0.0) - truth.getOrDefault(k, 0.0))));
        }
        Files.write(out.resolve("before_after_SYNTHETIC.csv"), ba);
        List<String> wf = new ArrayList<>();
        wf.add("step,total_km,change_km,km_moved_between_days");
        for (Step s : steps) wf.add(String.format("\"%s\",%.4f,%.4f,%.4f", s.fixLabel(), s.totalKm(), s.deltaKm(), s.movedBetweenDaysKm()));
        Files.write(out.resolve("root_cause_waterfall.csv"), wf);
        List<String> ck = new ArrayList<>();
        ck.add("check_id,name,count,finding");
        for (Check c : checks) ck.add(String.format("%s,\"%s\",%d,\"%s\"", c.id(), c.name(), c.count(), c.finding().replace("\"", "'")));
        Files.write(out.resolve("data_checks.csv"), ck);
    }

    static String html(List<Ping> pings, Config cfg, List<Check> checks, Map<DriverDay, Double> v1, Map<DriverDay, Double> v2,
                       Map<DriverDay, Double> truth, List<Step> steps, List<String[]> detection, List<String[]> matrix,
                       List<DistanceEngine.Rejection> rejected, boolean engineOk, double worst, boolean ok) {
        StringBuilder h = new StringBuilder();
        h.append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>GPS distance QA report (SYNTHETIC)</title><style>")
         .append("body{font-family:system-ui,Segoe UI,Arial,sans-serif;max-width:1100px;margin:24px auto;padding:0 16px;color:#1c1c1c;line-height:1.45}")
         .append("h1{font-size:24px;margin-bottom:4px}h2{font-size:18px;margin-top:32px;border-bottom:1px solid #ddd;padding-bottom:4px}")
         .append("table{border-collapse:collapse;width:100%;font-size:13px;margin:8px 0}th,td{border:1px solid #ddd;padding:5px 8px;text-align:left;vertical-align:top}")
         .append("th{background:#f4f4f4}td.n{text-align:right;font-variant-numeric:tabular-nums}.pass{color:#0a7a32;font-weight:600}.fail{color:#b3261e;font-weight:600}")
         .append(".banner{background:#fff4d6;border:1px solid #e6c46a;padding:8px 12px;border-radius:6px;font-size:13px}")
         .append(".cards{display:flex;gap:12px;flex-wrap:wrap}.card{flex:1;min-width:180px;border:1px solid #ddd;border-radius:8px;padding:10px 14px}")
         .append(".card .v{font-size:24px;font-weight:600}.muted{color:#666;font-size:12px}ul.ev{margin:4px 0;padding-left:18px;font-size:12px;color:#444}")
         .append("</style></head><body>");
        h.append("<h1>GPS distance QA report</h1><div class='banner'><b>SYNTHETIC DATA.</b> Generated by DataGenerator (seed ")
         .append(DataGenerator.SEED).append("). No real drivers, advertisers or locations. ").append(pings.size()).append(" pings.</div>");

        h.append("<div class='cards' style='margin-top:14px'>")
         .append(card("v1 billed (old)", String.format("%.2f km", total(v1)), "what the advertiser was charged"))
         .append(card("v2 billed (fixed)", String.format("%.2f km", total(v2)), String.format("%+.1f%% vs v1", (total(v2) / total(v1) - 1) * 100)));
        if (!truth.isEmpty()) h.append(card("True in-zone km", String.format("%.2f km", total(truth)), "from clean data before faults"))
                .append(card("v2 vs truth", String.format("<span class='%s'>%s</span>", ok ? "pass" : "fail", ok ? "PASS" : "FAIL"),
                        String.format("worst driver-day off by %.1f m", worst * 1000)));
        h.append("</div>");

        h.append("<h2>1. Problems found in the data</h2><table><tr><th>Check</th><th>Count</th><th>Finding</th></tr>");
        for (Check c : checks) {
            h.append("<tr><td><b>").append(c.id()).append("</b> ").append(esc(c.name())).append("</td><td class='n'>").append(c.count())
             .append("</td><td>").append(esc(c.finding())).append("<ul class='ev'>");
            for (String e : c.evidence()) h.append("<li>").append(esc(e)).append("</li>");
            h.append("</ul></td></tr>");
        }
        h.append("</table>");

        h.append("<h2>2. Root cause: how many km each bug is worth</h2><p class='muted'>The corrected engine with every fix switched off reproduces v1 exactly (")
         .append(engineOk ? "<span class='pass'>verified</span>" : "<span class='fail'>NOT verified</span>")
         .append("). Fixes are then switched on one at a time in pipeline order. \"Change\" = effect on the bill; \"Moved day\" = km that stayed billed but moved to the correct date. Attribution depends on order.</p>");
        h.append("<table><tr><th>Step</th><th>Total km</th><th>Change</th><th>Moved to correct day</th></tr>");
        for (Step s : steps) h.append(String.format("<tr><td>%s</td><td class='n'>%.3f</td><td class='n'>%+.3f</td><td class='n'>%.3f</td></tr>",
                esc(s.fixLabel()), s.totalKm(), s.deltaKm(), s.movedBetweenDaysKm()));
        h.append("</table>");

        h.append("<h2>3. Before and after, per driver per day</h2><p class='muted'>Days are report days (00:00 UTC+9). v1 books by UTC date, which is why it has rows dated 2026-11-01.</p>");
        h.append("<table><tr><th>Driver</th><th>Day</th><th>v1 old km</th><th>v2 fixed km</th>").append(truth.isEmpty() ? "" : "<th>True km</th><th>v2 - true (m)</th>").append("</tr>");
        for (DriverDay k : allKeys(v1, v2, truth)) {
            h.append(String.format("<tr><td>%s</td><td>%s</td><td class='n'>%.3f</td><td class='n'>%.3f</td>", k.driver(), k.day(), v1.getOrDefault(k, 0.0), v2.getOrDefault(k, 0.0)));
            if (!truth.isEmpty()) h.append(String.format("<td class='n'>%.3f</td><td class='n'>%+.1f</td>", truth.getOrDefault(k, 0.0),
                    (v2.getOrDefault(k, 0.0) - truth.getOrDefault(k, 0.0)) * 1000));
            h.append("</tr>");
        }
        h.append(String.format("<tr><th colspan=2>Total</th><th class='n'>%.3f</th><th class='n'>%.3f</th>", total(v1), total(v2)));
        if (!truth.isEmpty()) h.append(String.format("<th class='n'>%.3f</th><th></th>", total(truth)));
        h.append("</tr></table>");

        if (!detection.isEmpty()) {
            h.append("<h2>4. Did v2 reject exactly the faulty pings? (answer key)</h2><table><tr><th>Fault type</th><th>Injected</th><th>Handled correctly</th><th>Missed / ids</th></tr>");
            for (String[] r : detection) h.append("<tr><td>").append(esc(r[0])).append("</td><td class='n'>").append(r[1]).append("</td><td class='n'>")
                    .append(r[2]).append("</td><td style='font-size:12px'>").append(esc(r[3])).append("</td></tr>");
            h.append("</table>");
        }

        h.append("<h2>5. Regression matrix</h2><p class='muted'>Expected: v1 FAIL, v2 PASS, v2 with that rule's fix removed FAIL (proves the test has teeth). Same cases run by <code>mvn test</code>.</p>");
        h.append("<table><tr><th>ID</th><th>Rule</th><th>What it checks</th><th>v1 old</th><th>v2 fixed</th><th>v2 minus fix</th></tr>");
        for (String[] r : matrix) h.append("<tr><td>").append(r[0]).append("</td><td>").append(esc(r[1])).append("</td><td>").append(esc(r[2]))
                .append("</td>").append(pf(r[3])).append(pf(r[4])).append(pf(r[5])).append("</tr>");
        h.append("</table>");

        h.append("<h2>6. Map of rejected pings</h2>").append(map(cfg, rejected)).append("</body></html>");
        return h.toString();
    }

    static String map(Config cfg, List<DistanceEngine.Rejection> rejected) {
        double refLat = cfg.road().get(0)[0];
        double minLat = 37.485, maxLat = 37.56, minLon = 126.99, maxLon = 127.13;
        double[] lo = Geo.toXY(minLat, minLon, refLat), hi = Geo.toXY(maxLat, maxLon, refLat);
        double w = 1000, scale = w / (hi[0] - lo[0]), hgt = (hi[1] - lo[1]) * scale;
        java.util.function.BiFunction<Double, Double, String> pt = (lat, lon) -> {
            double[] p = Geo.toXY(lat, lon, refLat);
            return String.format("%.1f,%.1f", (p[0] - lo[0]) * scale, hgt - (p[1] - lo[1]) * scale);
        };
        StringBuilder s = new StringBuilder(String.format("<svg viewBox='0 0 %.0f %.0f' style='width:100%%;border:1px solid #ddd;background:#fafafa'>", w, hgt));
        String z1 = pt.apply(cfg.zoneMaxLat(), cfg.zoneMinLon()), z2 = pt.apply(cfg.zoneMinLat(), cfg.zoneMaxLon());
        String[] a = z1.split(","), b = z2.split(",");
        s.append(String.format("<rect x='%s' y='%s' width='%.1f' height='%.1f' fill='#2f6fde' fill-opacity='0.08' stroke='#2f6fde' stroke-dasharray='6 4'/>",
                a[0], a[1], Double.parseDouble(b[0]) - Double.parseDouble(a[0]), Double.parseDouble(b[1]) - Double.parseDouble(a[1])));
        s.append("<polyline fill='none' stroke='#555' stroke-width='3' points='");
        for (double[] p : cfg.road()) s.append(pt.apply(p[0], p[1])).append(' ');
        s.append("'/>");
        for (DistanceEngine.Rejection r : rejected) {
            if (r.reason() == DistanceEngine.Reason.DUPLICATE) continue;
            String[] p = pt.apply(r.ping().lat(), r.ping().lon()).split(",");
            String col = r.reason() == DistanceEngine.Reason.OFF_ROAD ? "#d03b2a" : "#e08a00";
            s.append(String.format("<circle cx='%s' cy='%s' r='4' fill='%s'><title>%s %s %s %s</title></circle>", p[0], p[1], col,
                    r.ping().id(), r.ping().driver(), r.reason(), esc(r.detail())));
        }
        s.append("<g font-size='14'><rect x='12' y='12' width='12' height='12' fill='#2f6fde' fill-opacity='0.2' stroke='#2f6fde'/><text x='30' y='23'>Campaign zone</text>")
         .append("<line x1='12' y1='42' x2='24' y2='42' stroke='#555' stroke-width='3'/><text x='30' y='47'>Road</text>")
         .append("<circle cx='18' cy='66' r='5' fill='#d03b2a'/><text x='30' y='71'>Rejected: off road</text>")
         .append("<circle cx='18' cy='90' r='5' fill='#e08a00'/><text x='30' y='95'>Rejected: impossible speed (on road)</text></g>");
        return s.append("</svg><p class='muted'>Hover a dot for ping id and reason. Duplicates are not drawn (they sit on top of the original).</p>").toString();
    }

    // ---- helpers ----
    static String card(String t, String v, String sub) {
        return "<div class='card'><div class='muted'>" + t + "</div><div class='v'>" + v + "</div><div class='muted'>" + sub + "</div></div>";
    }

    static String pf(String r) {
        return "<td class='" + (r.equals("PASS") ? "pass" : "fail") + "'>" + r + "</td>";
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String arg(String[] a, int i, String d) {
        return a.length > i ? a[i] : d;
    }

    static Map<String, List<Ping>> groupByDriver(List<Ping> pings) {
        Map<String, List<Ping>> m = new LinkedHashMap<>();
        for (Ping p : pings) m.computeIfAbsent(p.driver(), k -> new ArrayList<>()).add(p);
        return m;
    }

    @SafeVarargs
    static Set<DriverDay> allKeys(Map<DriverDay, Double>... maps) {
        Set<DriverDay> k = new TreeSet<>();
        for (Map<DriverDay, Double> m : maps) k.addAll(m.keySet());
        return k;
    }

    static double total(Map<DriverDay, Double> m) {
        return m.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    static double maxDiff(Map<DriverDay, Double> a, Map<DriverDay, Double> b) {
        double worst = 0;
        for (DriverDay k : allKeys(a, b)) worst = Math.max(worst, Math.abs(a.getOrDefault(k, 0.0) - b.getOrDefault(k, 0.0)));
        return worst;
    }

    static Map<String, String> readLabels(Path f) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(f);
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split(",");
            m.put(p[1], p[2]);
        }
        return m;
    }

    static Map<DriverDay, Double> readTruth(Path f) throws IOException {
        Map<DriverDay, Double> m = new TreeMap<>();
        List<String> lines = Files.readAllLines(f);
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split(",");
            m.put(new DriverDay(p[1], LocalDate.parse(p[2])), Double.parseDouble(p[3]));
        }
        return m;
    }
}
