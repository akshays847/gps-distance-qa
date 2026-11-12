package qa;

import java.time.Instant;

/**
 * One GPS ping from a driver's phone.
 * ts         = when the phone took the reading (UTC)  -> this is what the rules say to order by
 * receivedAt = when the server received it (UTC)      -> file order; late pings arrive out of order
 */
public record Ping(String id, String driver, Instant ts, double lat, double lon, Instant receivedAt) {}
