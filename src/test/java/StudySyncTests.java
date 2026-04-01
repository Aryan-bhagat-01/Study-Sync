import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class StudySyncTests {

    private static final String ENV_FILE = ".env";
    private static final String HIDDEN_FILE = ".hidden_assignments";

    @AfterEach
    void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get(ENV_FILE));
        Files.deleteIfExists(Paths.get(HIDDEN_FILE));
    }

    @Test
    void testExtractBaseUrlStandard() {
        String url = "https://uml.instructure.com/feeds/calendar.ics";
        assertEquals("https://uml.instructure.com", CanvasSetup.extractBaseUrl(url));
    }

    @Test
    void testExtractBaseUrlPort() {
        String url = "https://example.com:8443/calendar/feed.ics";
        assertEquals("https://example.com:8443", CanvasSetup.extractBaseUrl(url));
    }

    @Test
    void testCountOccurrences() {
        String data = "BEGIN:VEVENT...BEGIN:VEVENT...BEGIN:VEVENT";
        assertEquals(3, CanvasSetup.countOccurrences(data, "BEGIN:VEVENT"));
    }

    @Test
    void testCountOccurrencesNone() {
        assertEquals(0, CanvasSetup.countOccurrences("BEGIN:VCALENDAR", "BEGIN:VEVENT"));
    }

    @Test
    void testWriteEnvFile() throws IOException {
        CanvasSetup.writeEnvFile("https://uml.instructure.com", "https://uml.instructure.com/feed.ics");
        String content = Files.readString(Paths.get(ENV_FILE));
        assertTrue(content.contains("CANVAS_URL=https://uml.instructure.com"));
    }

    @Test
    void testCleanDescriptionLinks() {
        String raw = "Submit at: https://canvas.edu/test now";
        assertEquals("Submit at:  now", CanvasViewer.cleanDescription(raw));
    }

    @Test
    void testCleanDescriptionEscaping() {
        String raw = "Notes\\, and\\; semicolons";
        assertEquals("Notes, and; semicolons", CanvasViewer.cleanDescription(raw));
    }

    @Test
    void testTruncateShort() {
        assertEquals("Hello", CanvasViewer.truncate("Hello", 10));
    }

    @Test
    void testTruncateLong() {
        String result = CanvasViewer.truncate("This is a very long title", 10);
        assertEquals(12, result.length());
        assertTrue(result.endsWith("..."));
    }

    @Test
    void testParseEmptyAssignments() {
        List<CanvasViewer.Assignment> list = CanvasViewer.parseAssignments("");
        assertTrue(list.isEmpty());
    }

    @Test
    void testParseDateZulu() {
        LocalDateTime dt = CanvasViewer.parseDate("20260401T120000Z");
        assertNotNull(dt);
        assertEquals(2026, dt.getYear());
    }

    @Test
    void testParseDateOnly() {
        LocalDateTime dt = CanvasViewer.parseDate("20260401");
        assertEquals(0, dt.getHour());
    }

    @Test
    void testUrgencyOverdue() {
        LocalDateTime now = LocalDateTime.now();
        assertEquals("[OVERDUE]", CanvasViewer.getUrgencyTag(now.minusDays(1), now));
    }

    @Test
    void testUrgencyToday() {
        LocalDateTime now = LocalDateTime.now();
        assertEquals("[DUE TODAY]", CanvasViewer.getUrgencyTag(now.plusHours(1), now));
    }

    @Test
    void testUrgencyWeekly() {
        LocalDateTime now = LocalDateTime.now();
        assertEquals("[THIS WEEK]", CanvasViewer.getUrgencyTag(now.plusDays(4), now));
    }

    @Test
    void testGetFrequencyDefault() {
        assertEquals(1, StudySyncBot.getFrequency());
    }

    @Test
    void testSetFrequency() throws IOException {
        StudySyncBot.writeEnvValue("POST_FREQUENCY_HOURS", "12");
        assertEquals(12, StudySyncBot.getFrequency());
    }

    @Test
    void testHideAssignment() throws IOException {
        StudySyncBot.hideAssignment("Final Exam");
        Set<String> hidden = StudySyncBot.loadHiddenAssignments();
        assertTrue(hidden.contains("Final Exam"));
    }

    @Test
    void testRemoveEnvValue() throws IOException {
        StudySyncBot.writeEnvValue("CANVAS_FEED_URL", "https://canvas.ics");
        StudySyncBot.removeEnvValue("CANVAS_FEED_URL");
        assertNull(StudySyncBot.readEnvValue("CANVAS_FEED_URL"));
    }

    @Test
    void testLoadHiddenEmpty() throws IOException {
        Set<String> hidden = StudySyncBot.loadHiddenAssignments();
        assertTrue(hidden.isEmpty());
    }
}
