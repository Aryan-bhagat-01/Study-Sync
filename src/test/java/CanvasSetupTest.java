import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class CanvasSetupTest {

    @Test
    void testExtractBaseUrlBasic() {
        String url = "https://uml.instructure.com/feeds/calendar.ics";
        assertEquals("https://uml.instructure.com", CanvasSetup.extractBaseUrl(url));
    }

    @Test
    void testExtractBaseUrlWithPort() {
        String url = "https://example.com:8443/calendar/feed.ics";
        assertEquals("https://example.com:8443", CanvasSetup.extractBaseUrl(url));
    }

    @Test
    void testCountOccurrencesMultipleEvents() {
        String text = "BEGIN:VEVENT something BEGIN:VEVENT something BEGIN:VEVENT";
        assertEquals(3, CanvasSetup.countOccurrences(text, "BEGIN:VEVENT"));
    }

    @Test
    void testCountOccurrencesNoMatches() {
        String text = "BEGIN:VCALENDAR";
        assertEquals(0, CanvasSetup.countOccurrences(text, "BEGIN:VEVENT"));
    }

    @Test
    void testWriteEnvFileCreatesValues() throws IOException {
        Path envPath = Path.of(".env");
        Files.deleteIfExists(envPath);

        CanvasSetup.writeEnvFile("https://uml.instructure.com", "https://uml.instructure.com/feed.ics");

        String content = Files.readString(envPath);
        assertTrue(content.contains("CANVAS_URL=https://uml.instructure.com"));
        assertTrue(content.contains("CANVAS_FEED_URL=https://uml.instructure.com/feed.ics"));

        Files.deleteIfExists(envPath);
    }
}
