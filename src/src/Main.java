import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

public class Main {

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
                "ping", "-c", "1", "-W", "1", "-I", "wlan0", host);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process p = pb.start();
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    static void log(Instant start) throws IOException, InterruptedException {


        Instant end = null;

        String report = "START " + start.atZone(ZoneId.of("America/Edmonton"))
                .truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                + "\n";

        boolean down = true;

        File file = new File(System.getProperty("user.home") + "/outages/outages-" + YearMonth.now(ZoneId.of("America/Edmonton")) + ".txt");

        write(report,file);






        while (down){

            String time = ZonedDateTime.now(ZoneId.of("America/Edmonton"))
                    .truncatedTo(ChronoUnit.SECONDS)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            if (internetDown()){
                 report = "DOWN" + " " + time + "\n";
            } else {
                 report = "UP" + " " + time + "\n";
                end = Instant.now();
                down = false;
            }
            write(report,file);

            Thread.sleep(3000); // 3 seconds
        }

        Duration downtime = Duration.between(start,end);
        long minutes = downtime.toMinutes();
        int seconds = downtime.toSecondsPart();
        report = "---Total downtime = " + minutes + "m" + seconds + "s --- \n";
        write(report,file);

    }

    static void write(String report, File file){
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
