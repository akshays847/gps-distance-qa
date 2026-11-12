package qa;

/**
 * Each fix in the corrected calculation, in the order the pipeline applies them.
 * Every fix can be switched off on its own; that is how the tool measures
 * how many km each bug is worth, and how the tests prove each fix is needed.
 */
public enum Fix {
    DEDUPE("Duplicates counted once", "same driver + same timestamp = one ping (first received wins)"),
    SORT_BY_TIMESTAMP("Late / out-of-order pings placed by own timestamp", "sort by ts_utc, not arrival order"),
    DROP_OFF_ROAD("GPS noise: >100 m off road ignored", "distance to road polyline > 100 m"),
    DROP_IMPOSSIBLE_SPEED("GPS noise: >150 km/h ignored", "speed vs the last VALID ping > 150 km/h"),
    GREAT_CIRCLE_DISTANCE("Correct distance formula", "haversine instead of flat degrees"),
    CLIP_AT_ZONE_BOUNDARY("Only distance inside the zone", "segment clipped at the zone edge"),
    LOCAL_DAY_CUT("Day cut at 00:00 UTC+9", "segments split at local midnight");

    public final String label;
    public final String rule;

    Fix(String label, String rule) {
        this.label = label;
        this.rule = rule;
    }
}
