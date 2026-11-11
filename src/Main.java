import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

public class Main {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");
    static final String IFACE = "wlan0";

    public static void main(String[] args) throws IOException, InterruptedException {
        int failed = 0;
        Instant start = null;
        while (true){

            if (internetDown()){
                failed +=1;
                if(failed == 1){
                    start = Instant.now();
                }
                if(failed > 3){
                    log(start);
                    failed = 0;
                }
            } else {
                failed = 0;
            }

            Thread.sleep(3000); // 3 seconds
        }
    }

    static boolean internetDown() throws IOException, InterruptedException {
        for (String host : new String[] {"1.1.1.1", "8.8.8.8"}) {
            if (ping(host) == 0) return false;
        }
        return true;
    }

    static int ping(String host) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ping", "-c", "1", "-W", "1", "-I", IFACE, host);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process p = pb.start();
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    // True if the Pi itself is connected to the Wi-Fi network. When this is false, a failed
    // ping means the Pi lost Wi-Fi, not necessarily that the internet is down.
    static boolean wifiConnected() {
        try {
            return Files.readString(Path.of("/sys/class/net/" + IFACE + "/operstate")).trim().equals("up");
        } catch (IOException e) {
            return false;
        }
    }

    static String now() {
        return format(Instant.now());
    }

    static String format(Instant t) {
        return t.atZone(ZONE).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static void log(Instant start) throws IOException, InterruptedException {

        YearMonth month = YearMonth.now(ZONE);
        write("START " + format(start) + "\n");
        write("DOWN " + now() + "\n"); // written once; the outage lasts until the UP line

        boolean wifiWasUp = true;

        while (true){

            // The log file follows the current month, so an outage that runs past the end
            // of a month continues in the new month's file.
            if (!YearMonth.now(ZONE).equals(month)) {
                month = YearMonth.now(ZONE);
                write("CONTINUED outage that started " + format(start) + "\n");
            }

            boolean wifiUp = wifiConnected();
            if (wifiUp != wifiWasUp) {
                write((wifiUp ? "WIFI-RECONNECTED " : "WIFI-DISCONNECTED ") + now() + "\n");
                wifiWasUp = wifiUp;
            }

            if (!internetDown()) {
                break;
            }

            Thread.sleep(3000); // 3 seconds
        }

        Instant end = Instant.now();
        write("UP " + format(end) + "\n");

        Duration downtime = Duration.between(start, end);
        String total = (downtime.toHours() > 0 ? downtime.toHours() + "h" : "")
                + downtime.toMinutesPart() + "m" + downtime.toSecondsPart() + "s";
        write("---Total downtime = " + total + " --- \n");

    }

    static void write(String report){
        File file = new File(System.getProperty("user.home") + "/outages/outages-" + YearMonth.now(ZONE) + ".txt");
        file.getParentFile().mkdirs();
        boolean isNew = !file.exists();

        try (FileWriter writer = new FileWriter(file, true)) {
            if (isNew) {
                writer.write("status  timestamp\n");
            }
            writer.write(report);
        } catch (IOException e) {
            System.err.println("Failed to write log: " + e.getMessage());
        }
    }
}
