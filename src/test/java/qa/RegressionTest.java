package qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REGRESSION CHECK - one test per rule.
 * Each test asserts three things:
 *   1. the fixed calculation (v2) PASSES
 *   2. the old calculation (v1) FAILS      -> "fails against the old behaviour"
 *   3. v2 with just this rule's fix removed FAILS -> proves the test really guards that rule
 */
class RegressionTest {

    private void check(Scenarios.Scenario s) {
        Scenarios.Outcome fixed = Scenarios.evaluate(s, DistanceEngine.fixed(s.config()));
        assertTrue(fixed.pass(), () -> s.id() + " v2 should pass: " + fixed.detail());

        Scenarios.Outcome legacy = Scenarios.evaluate(s, new LegacyCalculator(s.config()));
        assertFalse(legacy.pass(), () -> s.id() + " v1 should FAIL but passed");

        Scenarios.Outcome withoutFix = Scenarios.evaluate(s, DistanceEngine.fixedWithout(s.config(), s.guards()));
        assertFalse(withoutFix.pass(), () -> s.id() + " has no teeth: passes even without " + s.guards());
    }

    @Test @DisplayName("S1 noise: ping >100 m off road is ignored")
    void noiseOffRoad() { check(Scenarios.noiseOffRoad()); }

    @Test @DisplayName("S2 noise: >150 km/h spike ignored, next good ping kept")
    void noiseSpeedSpike() { check(Scenarios.noiseSpeedSpike()); }

    @Test @DisplayName("S3 duplicates count once")
    void duplicates() { check(Scenarios.duplicates()); }

    @Test @DisplayName("S4 late pings placed by own timestamp")
    void latePings() { check(Scenarios.latePings()); }

    @Test @DisplayName("S5 day cut at 00:00 UTC+9")
    void dayCut() { check(Scenarios.dayCutUtcPlus9()); }

    @Test @DisplayName("S6 only distance inside the zone counts")
    void zoneBoundary() { check(Scenarios.zoneBoundary()); }

    @Test @DisplayName("S7 distance formula (haversine, not flat degrees)")
    void distanceFormula() { check(Scenarios.distanceFormula()); }
}
