import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

public class CanvasViewer {

    private static final String ENV_FILE = ".env";

    public static void main(String[] args) throws Exception {
        String feedUrl = readEnvValue("CANVAS_FEED_URL");

        if (feedUrl == null || feedUrl.isBlank()) {
            System.err.println("Error: CANVAS_FEED_URL not found in .env or environment");
            System.err.println("Run CanvasSetup first to save your feed URL.");
            System.exit(1);
        }

        System.out.println("Fetching your Canvas calendar...\n");

        String icalData = fetchFeed(feedUrl);
        List<Assignment> assignments = parseAssignments(icalData);

        if (assignments.isEmpty()) {
            System.out.println("No upcoming assignments found.");
            return;
        }

        assignments.sort(Comparator.comparing(a -> a.dueDate != null ? a.dueDate : LocalDateTime.MAX));
        printAssignments(assignments);
    }

    static String fetchFeed(String feedUrl) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(feedUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "text/calendar")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("Error: Canvas returned HTTP " + response.statusCode());
            System.exit(1);
        }

        return response.body();
    }

    static List<Assignment> parseAssignments(String icalData) {
        List<Assignment> assignments = new ArrayList<>();
        String[] lines = icalData.split("\\r?\\n");

        Assignment current = null;
        StringBuilder descBuffer = new StringBuilder();
        boolean inDesc = false;

        List<String> unfolded = new ArrayList<>();
        for (String line : lines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && !unfolded.isEmpty()) {
                String prev = unfolded.remove(unfolded.size() - 1);
                unfolded.add(prev + line.substring(1));
            } else {
                unfolded.add(line);
            }
        }

        for (String line : unfolded) {
            if (line.equals("BEGIN:VEVENT")) {
                current = new Assignment();
                descBuffer.setLength(0);
                inDesc = false;

            } else if (line.equals("END:VEVENT") && current != null) {
                current.description = cleanDescription(descBuffer.toString());
                if (current.title != null && !current.title.isBlank()) {
                    assignments.add(current);
                }
                current = null;
                inDesc = false;

            } else if (current != null) {
                if (line.startsWith("SUMMARY:")) {
                    current.title = line.substring(8).trim();
                    inDesc = false;

                } else if (line.startsWith("DUE") || line.startsWith("DTEND") || line.startsWith("DTSTART")) {
                    String value = extractValue(line);
                    LocalDateTime parsed = parseDate(value);

                    if (line.startsWith("DUE")) {
                        current.dueDate = parsed;
                    } else if (line.startsWith("DTEND") && current.dueDate == null) {
                        current.dueDate = parsed;
                    } else if (line.startsWith("DTSTART") && current.dueDate == null) {
                        current.dueDate = parsed;
                    }
                    inDesc = false;

                } else if (line.startsWith("DESCRIPTION:")) {
                    descBuffer.append(line.substring(12).trim());
                    inDesc = true;

                } else if (line.startsWith("URL:")) {
                    current.url = line.substring(4).trim();
                    inDesc = false;

                } else if (inDesc) {
                    descBuffer.append(line);
                }
            }
        }

        return assignments;
    }

    static String extractValue(String line) {
        int colon = line.indexOf(':');
        if (colon == -1) return "";
        return line.substring(colon + 1).trim();
    }

    static LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        raw = raw.trim();
        try {
            if (raw.endsWith("Z")) {
                return LocalDateTime.parse(raw.replace("Z", ""),
                        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            } else if (raw.contains("T")) {
                return LocalDateTime.parse(raw,
                        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            } else if (raw.length() == 8) {
                LocalDate date = LocalDate.parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd"));
                return date.atTime(23, 59);
            }
        } catch (Exception e) {
        }
        return null;
    }

    static String cleanDescription(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw
                .replace("\\n", " ")
                .replace("\\,", ",")
                .replace("\\;", ";")
                .replaceAll("https?://\\S+", "")
                .trim();
    }

    static void printAssignments(List<Assignment> assignments) {
        DateTimeFormatter display = DateTimeFormatter.ofPattern("MMM dd, yyyy  hh:mm a");
        LocalDateTime now = LocalDateTime.now();

        String divider = "-".repeat(60);
        String header  = "-".repeat(22) + "  CANVAS ASSIGNMENTS  " + "-".repeat(18);

        System.out.println(header);
        System.out.printf("  %-3s  %-32s  %s%n", "#", "Assignment", "Due Date");
        System.out.println(divider);

        int count = 0;
        for (Assignment a : assignments) {
            count++;
            String title   = truncate(a.title != null ? a.title : "(No title)", 30);
            String dueStr  = a.dueDate != null ? a.dueDate.format(display) : "No due date";
            String urgency = getUrgencyTag(a.dueDate, now);

            System.out.printf("  %-3d  %-32s  %s  %s%n", count, title, dueStr, urgency);

            if (a.description != null && !a.description.isBlank()) {
                String desc = truncate(a.description, 55);
                System.out.printf("       -> %s%n", desc);
            }
        }

        System.out.println(divider);
        System.out.printf("  %d assignment(s) total%n", count);
    }

    static String getUrgencyTag(LocalDateTime due, LocalDateTime now) {
        if (due == null) return "";
        long days = Duration.between(now, due).toDays();
        if (due.isBefore(now)) return "[OVERDUE]";
        if (days == 0)         return "[DUE TODAY]";
        if (days == 1)         return "[DUE TOMORROW]";
        if (days <= 7)         return "[THIS WEEK]";
        return "";
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "...";
    }

    static String readEnvValue(String key) throws IOException {
        String envVal = System.getenv(key);
        if (envVal != null && !envVal.isBlank()) return envVal;

        Path path = Paths.get(ENV_FILE);
        if (!Files.exists(path)) return null;
        for (String line : Files.readAllLines(path)) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    static class Assignment {
        String title;
        LocalDateTime dueDate;
        String description;
        String url;
    }
}