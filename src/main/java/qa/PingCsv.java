package qa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Reads/writes the ping CSV. Every row carries data_label=SYNTHETIC. */
public final class PingCsv {
    public static final String HEADER = "data_label,ping_id,driver_id,ts_utc,lat,lon,received_at_utc";

    private PingCsv() {}

    /** Returns pings in FILE order (= order the server received them). */
    public static List<Ping> read(Path file) throws IOException {
        List<Ping> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] f = line.split(",");
            out.add(new Ping(f[1], f[2], Instant.parse(f[3]),
                    Double.parseDouble(f[4]), Double.parseDouble(f[5]), Instant.parse(f[6])));
        }
        return out;
    }

    public static void write(Path file, List<Ping> pings) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        for (Ping p : pings) {
            lines.add(String.format("SYNTHETIC,%s,%s,%s,%.7f,%.7f,%s",
                    p.id(), p.driver(), p.ts(), p.lat(), p.lon(), p.receivedAt()));
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, lines);
    }
}
