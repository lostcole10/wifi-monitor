import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Stream;

/**
 * Reads the outage logs written by Main and prints a summary.
 *
 * Usage: java Analyze.java [log folder]   (default: ~/outages)
 *
 * Outages where the Pi itself lost Wi-Fi (a WIFI-DISCONNECTED or NOTE line) are not real
 * internet outages, so they are listed separately and left out of the statistics.
 */
public class Analyze {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");

    record Outage(OffsetDateTime start, OffsetDateTime end, boolean piProblem) {
        Duration duration() { return Duration.between(start, end); }
        ZonedDateTime localStart() { return start.atZoneSameInstant(ZONE); }
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : System.getProperty("user.home") + "/outages");
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().matches("outages-\\d{4}-\\d{2}\\.txt")).sorted().toList();
        }
        if (files.isEmpty()) {
            System.out.println("No outage logs found in " + dir);
            return;
        }

        List<Outage> all = parse(files);
        List<Outage> real = all.stream().filter(o -> !o.piProblem()).toList();
        List<Outage> pi = all.stream().filter(Outage::piProblem).toList();

        System.out.println("Outage report for " + dir + " (" + files.size() + " log file(s))");
        if (real.isEmpty()) {
            System.out.println("\nNo internet outages recorded.");
        } else {
            OffsetDateTime first = real.get(0).start();
            OffsetDateTime last = real.get(real.size() - 1).end();
            System.out.println("Covering " + first.toLocalDate() + " to " + last.toLocalDate());

            section("Overall");
            summary(real);

            section("By month");
            Map<YearMonth, List<Outage>> byMonth = new TreeMap<>();
            for (Outage o : real) byMonth.computeIfAbsent(YearMonth.from(o.localStart()), k -> new ArrayList<>()).add(o);
            System.out.printf("%-8s %7s %10s %10s %10s%n", "month", "count", "total", "average", "longest");
            for (var e : byMonth.entrySet()) {
                List<Outage> m = e.getValue();
                System.out.printf("%-8s %7d %10s %10s %10s%n", e.getKey(), m.size(),
                        fmt(total(m)), fmt(total(m).dividedBy(m.size())), fmt(longest(m).duration()));
            }

            section("By hour of day (outage start, local time)");
            int[] byHour = new int[24];
            for (Outage o : real) byHour[o.localStart().getHour()]++;
            for (int h = 0; h < 24; h++) bar(String.format("%02d:00", h), byHour[h], max(byHour));

            section("By day of week");
            int[] byDay = new int[7];
            for (Outage o : real) byDay[o.localStart().getDayOfWeek().getValue() - 1]++;
            for (DayOfWeek d : DayOfWeek.values())
                bar(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), byDay[d.getValue() - 1], max(byDay));

            section("Outages per day");
            Map<LocalDate, Integer> perDay = new TreeMap<>();
            LocalDate lastDay = real.get(real.size() - 1).localStart().toLocalDate();
            for (LocalDate d = real.get(0).localStart().toLocalDate(); !d.isAfter(lastDay); d = d.plusDays(1)) perDay.put(d, 0);
            for (Outage o : real) perDay.merge(o.localStart().toLocalDate(), 1, Integer::sum);
            int maxPerDay = perDay.values().stream().max(Integer::compare).orElse(0);
            for (var e : perDay.entrySet()) bar(e.getKey().toString(), e.getValue(), maxPerDay);

            section("Longest outages");
            real.stream().sorted(Comparator.comparing(Outage::duration).reversed()).limit(5)
                    .forEach(o -> System.out.printf("%10s  starting %s%n", fmt(o.duration()), o.localStart().toLocalDateTime()));
        }

        if (!pi.isEmpty()) {
            section("Excluded: the Pi lost its own Wi-Fi");
            for (Outage o : pi)
                System.out.printf("%10s  starting %s%n", fmt(o.duration()), o.localStart().toLocalDateTime());
        }
    }

    // Reads every file in order. An outage that continues into the next month's file
    // (a CONTINUED line) is joined back into one outage.
    static List<Outage> parse(List<Path> files) throws IOException {
        List<Outage> outages = new ArrayList<>();
        OffsetDateTime start = null;
        boolean piProblem = false;
        for (Path f : files) {
            int n = 0;
            for (String line : Files.readAllLines(f)) {
                n++;
                String[] parts = line.trim().split(" ", 2);
                try {
                    switch (parts[0]) {
                        case "START" -> {
                            if (start != null) warn(f, n, "START without an UP for the previous outage; skipping it");
                            start = OffsetDateTime.parse(parts[1].trim());
                            piProblem = false;
                        }
                        case "WIFI-DISCONNECTED", "NOTE" -> piProblem = true;
                        case "UP" -> {
                            if (start == null) { warn(f, n, "UP without a START"); continue; }
                            outages.add(new Outage(start, OffsetDateTime.parse(parts[1].trim()), piProblem));
                            start = null;
                        }
                        default -> { } // status header, DOWN, CONTINUED, WIFI-RECONNECTED, totals
                    }
                } catch (DateTimeException e) {
                    warn(f, n, "unreadable timestamp: " + line);
                }
            }
        }
        if (start != null) System.out.println("Note: an outage starting " + start + " is still in progress and is not counted.");
        return outages;
    }

    static void summary(List<Outage> list) {
        Duration total = total(list);
        long days = Math.max(1, Duration.between(list.get(0).start(), list.get(list.size() - 1).end()).toDays() + 1);
        System.out.println("Outages:          " + list.size());
        System.out.println("Total downtime:   " + fmt(total));
        System.out.println("Average outage:   " + fmt(total.dividedBy(list.size())));
        System.out.println("Median outage:    " + fmt(list.stream().map(Outage::duration).sorted().toList().get(list.size() / 2)));
        System.out.println("Longest outage:   " + fmt(longest(list).duration()) + " (" + longest(list).localStart().toLocalDateTime() + ")");
        System.out.printf ("Outages per day:  %.1f%n", (double) list.size() / days);
        System.out.printf ("Uptime:           %.3f%%%n", 100.0 * (1 - (double) total.toSeconds() / Duration.ofDays(days).toSeconds()));
    }

    static Duration total(List<Outage> list) {
        return list.stream().map(Outage::duration).reduce(Duration.ZERO, Duration::plus);
    }

    static Outage longest(List<Outage> list) {
        return list.stream().max(Comparator.comparing(Outage::duration)).orElseThrow();
    }

    static int max(int[] a) {
        return Arrays.stream(a).max().orElse(0);
    }

    static void bar(String label, int count, int max) {
        int width = max == 0 ? 0 : (int) Math.round(30.0 * count / max);
        System.out.printf("%-10s %4d %s%n", label, count, "#".repeat(width));
    }

    static void section(String title) {
        System.out.println("\n== " + title + " ==");
    }

    static void warn(Path f, int line, String msg) {
        System.err.println("Warning: " + f.getFileName() + " line " + line + ": " + msg);
    }

    static String fmt(Duration d) {
        long h = d.toHours();
        return (h > 0 ? h + "h" : "") + d.toMinutesPart() + "m" + d.toSecondsPart() + "s";
    }
}
