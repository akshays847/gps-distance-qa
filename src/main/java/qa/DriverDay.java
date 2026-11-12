package qa;

import java.time.LocalDate;

/** Billing key: one driver on one report day. */
public record DriverDay(String driver, LocalDate day) implements Comparable<DriverDay> {
    @Override
    public int compareTo(DriverDay o) {
        int c = driver.compareTo(o.driver);
        return c != 0 ? c : day.compareTo(o.day);
    }

    @Override
    public String toString() {
        return driver + " " + day;
    }
}
