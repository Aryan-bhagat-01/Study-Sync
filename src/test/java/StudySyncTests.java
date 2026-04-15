import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StudySyncTests {

    private static final String ENV_FILE = ".env";

    @AfterEach
    void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get(ENV_FILE));
        Files.deleteIfExists(Paths.get(StudySyncBot.DATA_FILE));
    }

    // ── CanvasSetup Tests 

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

    // ── CanvasViewer Tests 
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

    // ── StudySyncBot Tests 

    @Test
    void testGetOrCreateConfig() {
        StudySyncBot.ServerConfig config = StudySyncBot.getOrCreateConfig("guild123");
        assertNotNull(config);
        assertEquals(1, config.frequencyHours);
        assertNull(config.feedUrl);
    }

    @Test
    void testHideAssignmentPerServer() {
        StudySyncBot.ServerConfig config = new StudySyncBot.ServerConfig();
        config.hiddenAssignments.add("Final Exam");
        assertTrue(config.hiddenAssignments.contains("Final Exam"));
    }

    @Test
    void testUnhideAssignments() {
        StudySyncBot.ServerConfig config = new StudySyncBot.ServerConfig();
        config.hiddenAssignments.add("Final Exam");
        config.hiddenAssignments.add("Homework 1");
        config.hiddenAssignments.clear();
        assertTrue(config.hiddenAssignments.isEmpty());
    }

    @Test
    void testSaveAndLoadServerData() throws IOException {
        StudySyncBot.serverConfigs.clear();
        StudySyncBot.ServerConfig config = StudySyncBot.getOrCreateConfig("testGuild");
        config.feedUrl = "https://canvas.example.com/feed.ics";
        config.channelId = "123456789";
        config.frequencyHours = 6;
        config.hiddenAssignments.add("Hidden Assignment");

        StudySyncBot.saveAllServerData();
        StudySyncBot.serverConfigs.clear();
        StudySyncBot.loadAllServerData();

        StudySyncBot.ServerConfig loaded = StudySyncBot.serverConfigs.get("testGuild");
        assertNotNull(loaded);
        assertEquals("https://canvas.example.com/feed.ics", loaded.feedUrl);
        assertEquals("123456789", loaded.channelId);
        assertEquals(6, loaded.frequencyHours);
        assertTrue(loaded.hiddenAssignments.contains("Hidden Assignment"));
    }

    @Test
    void testDefaultFrequency() {
        StudySyncBot.ServerConfig config = new StudySyncBot.ServerConfig();
        assertEquals(1, config.frequencyHours);
    }

    @Test
    void testCountOccurrencesBot() {
        String data = "BEGIN:VEVENT...BEGIN:VEVENT";
        assertEquals(2, StudySyncBot.countOccurrences(data, "BEGIN:VEVENT"));
    }
}