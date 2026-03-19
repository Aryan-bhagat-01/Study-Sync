import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Scanner;

public class CanvasSetup {
    private static final String ENV_FILE = ".env";

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Paste your Canvas Calendar Feed URL (ends in .ics): ");
        String feedUrl = scanner.nextLine().trim();

        if (feedUrl.isEmpty()) {
            System.err.println("Error: URL cannot be empty.");
            System.exit(1);
        }

        if (!feedUrl.startsWith("http://") && !feedUrl.startsWith("https://")) {
            feedUrl = "https://" + feedUrl;
        }

        if (!feedUrl.endsWith(".ics")) {
            System.err.println("Error: This does not look like a Canvas iCal feed URL (should end in .ics).");
            System.exit(1);
        }

        String baseUrl = extractBaseUrl(feedUrl);
        System.out.println("Using Canvas base URL: " + baseUrl);
        System.out.println("Verifying feed URL...");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(feedUrl))
                .header("Accept", "text/calendar")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            System.err.println("Error: Could not reach the feed URL.");
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            System.err.println("Error: Canvas rejected the feed URL (HTTP " + response.statusCode() + ").");
            System.err.println("Make sure you copied the full link from the Calendar Feed dialog.");
            System.exit(1);
        }

        if (response.statusCode() != 200) {
            System.err.println("Error: Canvas returned HTTP " + response.statusCode());
            System.exit(1);
        }

        String body = response.body();
        if (!body.contains("BEGIN:VCALENDAR")) {
            System.err.println("Error: The URL did not return a valid iCal feed.");
            System.exit(1);
        }

        int eventCount = countOccurrences(body, "BEGIN:VEVENT");
        System.out.println("Feed verified. Found " + eventCount + " event(s) in your calendar.");

        writeEnvFile(baseUrl, feedUrl);
        System.out.println("Feed URL saved to " + ENV_FILE);
        System.out.println("You can now start the Discord bot.");
        scanner.close();
    }

    private static String extractBaseUrl(String url) {
        try {
            URI uri = URI.create(url);
            String base = uri.getScheme() + "://" + uri.getHost();
            if (uri.getPort() != -1) {
                base += ":" + uri.getPort();
            }
            return base;
        } catch (Exception e) {
            int slashIndex = url.indexOf("/", url.indexOf("//") + 2);
            return slashIndex != -1 ? url.substring(0, slashIndex) : url;
        }
    }

    private static int countOccurrences(String text, String target) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
    }

    private static void writeEnvFile(String baseUrl, String feedUrl) throws IOException {
        Path path = Paths.get(ENV_FILE);
        StringBuilder content = new StringBuilder();

        if (Files.exists(path)) {
            String existing = Files.readString(path);
            for (String line : existing.split("\n")) {
                if (!line.startsWith("CANVAS_URL=") && !line.startsWith("CANVAS_FEED_URL=")) {
                    content.append(line).append("\n");
                }
            }
        }

        content.append("CANVAS_URL=").append(baseUrl).append("\n");
        content.append("CANVAS_FEED_URL=").append(feedUrl).append("\n");
        Files.writeString(path, content.toString());
    }
}
