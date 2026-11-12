package qa;

import java.util.List;
import java.util.Map;

/** Anything that turns raw pings (in arrival order) into billable km per driver per day. */
public interface DistanceCalculator {
    String name();

    Map<DriverDay, Double> kmPerDriverDay(List<Ping> pingsInArrivalOrder);
}
