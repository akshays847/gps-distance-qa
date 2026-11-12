package qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end checks on the full synthetic dataset (regenerated in memory from the fixed seed). */
class DatasetTest {

    private final Config cfg = Config.standard();
    private final DataGenerator.Generated data = DataGenerator.generate(cfg, DataGenerator.SEED);

    @Test @DisplayName("Dataset has at least 2,000 pings and contains every fault type")
    void datasetShape() {
        assertTrue(data.arrivalOrder().size() >= 2000, "only " + data.arrivalOrder().size() + " pings");
        for (String t : List.of("OFF_ROAD", "SPEED_SPIKE", "DUPLICATE", "LATE")) {
            assertTrue(data.faultLabels().values().stream().anyMatch(v -> v.contains(t)), "no " + t + " faults injected");
        }
    }

    @Test @DisplayName("v2 matches the true km for every driver and day (within 50 m)")
    void fixedMatchesTruth() {
        Map<DriverDay, Double> v2 = DistanceEngine.fixed(cfg).kmPerDriverDay(data.arrivalOrder());
        double worst = QaChecker.maxDiff(v2, data.groundTruthKm());
        assertTrue(worst <= QaChecker.TRUTH_TOLERANCE_KM, "worst driver-day error " + worst + " km");
        // Each driver's two-day total within 10 m. Not exact: when a noisy ping is dropped, the real reading
        // at that moment is lost and v2 draws a straight line across ~3 m of GPS jitter (measured: <= 2.2 m).
        for (String d : List.of("D01", "D02", "D03", "D04", "D05")) {
            double got = v2.entrySet().stream().filter(e -> e.getKey().driver().equals(d)).mapToDouble(Map.Entry::getValue).sum();
            double exp = data.groundTruthKm().entrySet().stream().filter(e -> e.getKey().driver().equals(d)).mapToDouble(Map.Entry::getValue).sum();
            assertEquals(exp, got, 0.010, d + " total");
        }
    }

    @Test @DisplayName("v1 does NOT match the true km (the old behaviour is really wrong)")
    void legacyIsWrong() {
        Map<DriverDay, Double> v1 = new LegacyCalculator(cfg).kmPerDriverDay(data.arrivalOrder());
        assertFalse(QaChecker.maxDiff(v1, data.groundTruthKm()) <= QaChecker.TRUTH_TOLERANCE_KM, "v1 unexpectedly matches truth");
    }

    @Test @DisplayName("Engine with no fixes reproduces v1 exactly (so root-cause numbers are valid)")
    void engineWithoutFixesEqualsLegacy() {
        Map<DriverDay, Double> v1 = new LegacyCalculator(cfg).kmPerDriverDay(data.arrivalOrder());
        Map<DriverDay, Double> none = new DistanceEngine(cfg, EnumSet.noneOf(Fix.class)).kmPerDriverDay(data.arrivalOrder());
        assertTrue(QaChecker.maxDiff(v1, none) < 1e-9, "engine(no fixes) differs from v1");
    }

    @Test @DisplayName("v2 rejects every injected noise/duplicate ping and nothing else; late pings are kept")
    void rejectsExactlyTheFaultyPings() {
        DistanceEngine e = DistanceEngine.fixed(cfg);
        e.kmPerDriverDay(data.arrivalOrder());
        Set<String> rejected = new HashSet<>();
        e.rejections().forEach(r -> rejected.add(r.ping().id()));
        Set<String> shouldReject = new HashSet<>();
        data.faultLabels().forEach((id, f) -> {
            if (f.startsWith("OFF_ROAD") || f.startsWith("SPEED_SPIKE") || f.startsWith("DUPLICATE")) shouldReject.add(id);
        });
        assertEquals(shouldReject, rejected, "rejected set differs from answer key");
    }
}
