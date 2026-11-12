package qa;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * v1 - THE REFERENCE CALCULATION THAT GETS DISTANCE WRONG (kept unchanged on purpose).
 *
 * It looks reasonable, which is the point. Its faults:
 *   - walks pings in the order they ARRIVED, not by their own timestamp   (late pings)
 *   - never removes duplicates                                            (duplicates)
 *   - never filters GPS noise (off-road / impossible speed)               (noise)
 *   - books the day by the UTC date                                       (UTC+9 day cut)
 *   - counts a whole segment if its START is inside the zone              (zone boundary)
 *   - measures distance on flat degrees                                   (distance formula)
 */
public class LegacyCalculator implements DistanceCalculator {
    private final Config config;

    public LegacyCalculator(Config config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "v1 legacy";
    }

    @Override
    public Map<DriverDay, Double> kmPerDriverDay(List<Ping> pingsInArrivalOrder) {
        Map<DriverDay, Double> km = new TreeMap<>();
        Map<String, Ping> last = new HashMap<>();
        for (Ping p : pingsInArrivalOrder) {
            Ping prev = last.get(p.driver());
            if (prev != null && config.inZone(prev.lat(), prev.lon())) {
                double metres = Geo.flatDegreesMetres(prev.lat(), prev.lon(), p.lat(), p.lon());
                LocalDate day = prev.ts().atOffset(ZoneOffset.UTC).toLocalDate();
                km.merge(new DriverDay(p.driver(), day), metres / 1000.0, Double::sum);
            }
            last.put(p.driver(), p);
        }
        return km;
    }
}
