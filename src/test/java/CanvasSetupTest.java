import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class CanvasSetupTest {

    @Test
    void testTrue() {
        assertTrue(true);
    }

    @Test
    void testFalse() {
        assertFalse(false);
    }

    @Test
    void testEquality() {
        assertEquals(5, 2 + 3);
    }

    @Test
    void testNotNull() {
        String s = "hello";
        assertNotNull(s);
    }

    @Test
    void testString() {
        assertEquals("test", "te" + "st");
    }
}
