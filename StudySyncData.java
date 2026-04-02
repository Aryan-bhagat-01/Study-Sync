import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class StudySyncData {

    private static final String REPO_URL   = "https://github.com/Aryan-bhagat-01/Study-Sync.git";
    private static final String REPO_DIR   = ".";
    private static final String MAIN_CLASS = "com.studysync.Main";
    private static final String[] CSV_HEADER = {
        "run_number", "timestamp", "wall_clock_ms", "exit_code", "notes"
    };

    public static void main(String[] args) throws Exception {
        int    numRuns  = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        String baseName = args.length > 1 ? args[1] : "StudySyncRunTimeData";
        long   timeout  = args.length > 2 ? Long.parseLong(args[2]) : 30;

        String csvFile = baseName + ".csv";
        String svgFile = baseName + ".svg";

        log("Study-Sync Benchmarker");
        log("Runs: " + numRuns + " | Timeout: " + timeout + "s");

        cloneOrPull();
        buildWithMaven();

        String jarPath = findJar();
        log(jarPath != null ? "Running via JAR: " + jarPath : "Running via mvn exec:java");

        long[] wallMs = new long[numRuns];

        try (PrintWriter csv = new PrintWriter(new FileWriter(csvFile))) {
            csv.println(String.join(",", CSV_HEADER));

            for (int i = 0; i < numRuns; i++) {
                log(String.format("Run %d / %d ...", i + 1, numRuns));

                String timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

                long startNs = System.nanoTime();
                ProcessResult result = runStudySync(jarPath, timeout);
                wallMs[i] = (System.nanoTime() - startNs) / 1_000_000;

                log(String.format("  wall=%dms  exit=%d  %s",
                    wallMs[i], result.exitCode, result.notes));

                csv.printf("%d,%s,%d,%d,%s%n",
                    i + 1, timestamp, wallMs[i], result.exitCode, result.notes);
            }
        }

        log("CSV written: " + csvFile);
        generateSvg(svgFile, wallMs);
        log("Figure written: " + svgFile);
        printSummary(wallMs);
    }

    private static void cloneOrPull() throws Exception {
        Path repoPath = Paths.get(REPO_DIR);
        if (Files.exists(repoPath)) {
            log("Repo exists — pulling latest changes...");
            exec(List.of("git", "-C", REPO_DIR, "pull", "--ff-only"));
        } else {
            log("Cloning " + REPO_URL + " ...");
            exec(List.of("git", "clone", REPO_URL, REPO_DIR));
        }
    }

    private static void buildWithMaven() throws Exception {
        log("Building with Maven (skipping tests)...");
        exec(List.of("mvn.cmd", "-f", REPO_DIR + "/pom.xml",
            "clean", "package", "-DskipTests", "-q"));
        log("Build complete.");
    }

    private static String findJar() throws IOException {
        Path targetDir = Paths.get(REPO_DIR, "target");
        if (!Files.exists(targetDir)) return null;

        try (var stream = Files.list(targetDir)) {
            return stream
                .filter(p -> p.toString().endsWith(".jar")
                    && !p.toString().contains("sources"))
                .map(Path::toString)
                .findFirst()
                .orElse(null);
        }
    }

    private static ProcessResult runStudySync(String jarPath, long timeoutSec) throws Exception {
        List<String> cmd = jarPath != null
            ? List.of("java", "-Xms64m", "-Xmx256m", "-jar", jarPath)
            : List.of("mvn.cmd", "-f", REPO_DIR + "/pom.xml",
                "exec:java", "-Dexec.mainClass=" + MAIN_CLASS, "-q");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        Process process = pb.start();
        boolean finished = process.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(0, "timed_out_after_" + timeoutSec + "s");
        }

        int exitCode = process.exitValue();
        return new ProcessResult(exitCode, exitCode != 0 ? "non_zero_exit" : "");
    }

    private static void generateSvg(String path, long[] wallMs) throws IOException {
        int n      = wallMs.length;
        int svgW   = 900;
        int svgH   = 420;
        int padL   = 100;
        int padR   = 30;
        int padT   = 60;
        int padB   = 60;
        int chartW = svgW - padL - padR;
        int chartH = svgH - padT - padB;

        double barW  = (double) chartW / n * 0.6;
        double barGap = (double) chartW / n;

        long maxWall = Arrays.stream(wallMs).max().getAsLong();
        if (maxWall == 0) maxWall = 1;

        StringBuilder s = new StringBuilder();
        s.append(String.format(
            "<svg xmlns='http://www.w3.org/2000/svg' width='%d' height='%d'>\n", svgW, svgH));
        s.append(String.format(
            "  <rect width='%d' height='%d' fill='#f8f9fa'/>\n", svgW, svgH));

        s.append(String.format(
            "  <text x='%d' y='30' font-family='Arial' font-size='15' font-weight='bold' " +
            "text-anchor='middle' fill='#222'>StudySync — Time per Run</text>\n", svgW / 2));

        s.append(String.format(
            "  <rect x='%d' y='%d' width='%d' height='%d' fill='#ffffff' stroke='#ccc' stroke-width='1'/>\n",
            padL, padT, chartW, chartH));

        int steps = 5;
        for (int i = 0; i <= steps; i++) {
            long val  = maxWall * i / steps;
            int  yPos = padT + chartH - (chartH * i / steps);
            s.append(String.format(
                "  <line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#e0e0e0' stroke-width='1'/>\n",
                padL, yPos, padL + chartW, yPos));
            s.append(String.format(
                "  <text x='%d' y='%d' font-family='Arial' font-size='10' " +
                "text-anchor='end' fill='#777'>%d</text>\n",
                padL - 6, yPos + 4, val));
        }

        s.append(String.format(
            "  <line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#aaa' stroke-width='1'/>\n",
            padL, padT, padL, padT + chartH));

        s.append(String.format(
            "  <text transform='rotate(-90)' x='%d' y='%d' font-family='Arial' font-size='12' " +
            "text-anchor='middle' fill='#555'>Time (ms)</text>\n",
            -(padT + chartH / 2), padL - 55));

        for (int i = 0; i < n; i++) {
            double barH = (double) wallMs[i] / maxWall * chartH;
            double x    = padL + i * barGap + (barGap - barW) / 2;
            double y    = padT + chartH - barH;
            s.append(String.format(
                "  <rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' fill='#4A90D9' rx='2'/>\n",
                x, y, barW, barH));
            s.append(String.format(
                "  <text x='%.1f' y='%.1f' font-family='Arial' font-size='10' " +
                "text-anchor='middle' fill='#333'>%d</text>\n",
                x + barW / 2, y - 5, wallMs[i]));
            s.append(String.format(
                "  <text x='%.1f' y='%d' font-family='Arial' font-size='11' " +
                "text-anchor='middle' fill='#555'>%d</text>\n",
                x + barW / 2, padT + chartH + 16, i + 1));
        }

        s.append(String.format(
            "  <text x='%d' y='%d' font-family='Arial' font-size='11' " +
            "text-anchor='middle' fill='#555'>Run Number</text>\n",
            padL + chartW / 2, padT + chartH + 38));

        s.append("</svg>\n");
        Files.writeString(Paths.get(path), s.toString());
    }

    private static void printSummary(long[] wallMs) {
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE, sum = 0;
        for (long w : wallMs) {
            sum += w;
            if (w < min) min = w;
            if (w > max) max = w;
        }
        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("Runs : " + wallMs.length);
        System.out.printf( "Min  : %d ms%n", min);
        System.out.printf( "Max  : %d ms%n", max);
        System.out.printf( "Avg  : %.1f ms%n", (double) sum / wallMs.length);
    }

    private static void exec(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("Command failed: " + cmd);
    }

    private static void log(String msg) {
        System.out.printf("[%s] %s%n",
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), msg);
    }

    record ProcessResult(int exitCode, String notes) {}
}