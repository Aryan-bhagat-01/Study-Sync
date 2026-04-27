import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ConnectionTimes {

    private static final String FEED_URL = "https://canvas.uml.edu/feeds/calendars/user_Vh81yLhrhzwUs6x29ejtbhnvt0jX6ljSVdbi51OH.ics";
    private static final int NUM_RUNS = 10;

    @Test
    public void benchmark() throws Exception {
        String svgFile = "figures/ConnectionTimes.svg";
        long[] totalMs = new long[NUM_RUNS];

        for (int i = 0; i < NUM_RUNS; i++) {
            long start = System.nanoTime();
            boolean valid = FEED_URL.startsWith("https://") && FEED_URL.endsWith(".ics");
            if (valid) CanvasViewer.fetchFeed(FEED_URL);
            totalMs[i] = (System.nanoTime() - start) / 1_000_000;
        }

        generateSvg(svgFile, totalMs);
        System.out.println("\nFigure generated in: " + svgFile + "\n");
    }

    private static void generateSvg(String path, long[] totalMs) throws IOException {
        int n = totalMs.length;
        int svgW = 900;
        int svgH = 500;
        int padL = 100;
        int padR = 30;
        int padT = 70;
        int padB = 60;
        int chartW = svgW - padL - padR;
        int chartH = svgH - padT - padB;

        double barW   = (double) chartW / n * 0.6;
        double barGap = (double) chartW / n;

        long maxVal     = Arrays.stream(totalMs).max().getAsLong();
        long maxRounded = ((maxVal / 50) + 1) * 50;

        StringBuilder s = new StringBuilder();
        s.append(String.format(
            "<svg xmlns='http://www.w3.org/2000/svg' width='%d' height='%d'>\n", svgW, svgH));
        s.append(String.format(
            "  <rect width='%d' height='%d' fill='#f8f9fa'/>\n", svgW, svgH));
        s.append(String.format(
            "  <text x='%d' y='30' font-family='Arial' font-size='15' font-weight='bold' " +
            "text-anchor='middle' fill='#222'>Bot to Canvas API Connection Time</text>\n",
            svgW / 2));
        s.append(String.format(
            "  <rect x='%d' y='%d' width='%d' height='%d' fill='#ffffff' stroke='#ccc' stroke-width='1'/>\n",
            padL, padT, chartW, chartH));

        int steps = (int) (maxRounded / 200);
        for (int i = 0; i <= steps; i++) {
            long val = 200L * i;
            int  yPos = padT + chartH - (int)((double) val / maxRounded * chartH);
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
            double barH = (double) totalMs[i] / maxRounded * chartH;
            double x = padL + i * barGap + (barGap - barW) / 2;
            double y = padT + chartH - barH;
            s.append(String.format(
                "  <rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' fill='#4A90D9' rx='2'/>\n",
                x, y, barW, barH));
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
}