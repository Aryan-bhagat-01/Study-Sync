import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;

public class StudentSubmissions {

    private static final String[] NAMES = { "1", "2", "3", "4", "5", "6" };
    private static final double[] ONTIME_BEFORE = { 4, 4, 5, 5, 6, 6 };
    private static final double[] ONTIME_AFTER = { 5, 6, 6, 7, 7, 8 };
    private static final double[] LATE_BEFORE = { 3, 2, 1, 1, 0, 0 };
    private static final double[] LATE_AFTER = { 0, 0, 0, 0, 0, 0 };

    @Test
    public void generateCharts() throws Exception {
        generateChart("figures/OnTimeSubmissions.svg",
            "Before and After Study Sync - On-Time Submissions",
            "Days Early on Average", ONTIME_BEFORE, ONTIME_AFTER, 10);
        System.out.println("\nFigure generated in: figures/OnTimeSubmissions.svg\n");

        generateChart("figures/LateSubmissions.svg",
            "Before and After Study Sync - Late Submissions",
            "Days Late on Average", LATE_BEFORE, LATE_AFTER, 4);
        System.out.println("\nFigure generated in: figures/LateSubmissions.svg\n");
    }

    private static void generateChart(String path, String title, String yLabel, double[] before, double[] after, int maxVal) throws IOException {
        int n = NAMES.length;
        int svgW = 900;
        int svgH = 500;
        int padL = 100;
        int padR = 30;
        int padT = 70;
        int padB = 80;
        int chartW = svgW - padL - padR;
        int chartH = svgH - padT - padB;

        double barW   = (double) chartW / n * 0.3;
        double barGap = (double) chartW / n;

        StringBuilder s = new StringBuilder();
        s.append(String.format("<svg xmlns='http://www.w3.org/2000/svg' width='%d' height='%d'>\n", svgW, svgH));
        s.append(String.format("  <rect width='%d' height='%d' fill='#f8f9fa'/>\n", svgW, svgH));
        s.append(String.format(
            "  <text x='%d' y='35' font-family='Arial' font-size='15' font-weight='bold' " +
            "text-anchor='middle' fill='#222'>%s</text>\n", svgW / 2, title));
        s.append(String.format(
            "  <rect x='%d' y='%d' width='%d' height='%d' fill='#ffffff' stroke='#ccc' stroke-width='1'/>\n",
            padL, padT, chartW, chartH));

        for (int i = 0; i <= maxVal; i++) {
            int yPos = padT + chartH - (chartH * i / maxVal);
            s.append(String.format(
                "  <line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#e0e0e0' stroke-width='1'/>\n",
                padL, yPos, padL + chartW, yPos));
            s.append(String.format(
                "  <text x='%d' y='%d' font-family='Arial' font-size='10' text-anchor='end' fill='#777'>%d</text>\n",
                padL - 6, yPos + 4, i));
        }

        s.append(String.format(
            "  <line x1='%d' y1='%d' x2='%d' y2='%d' stroke='#aaa' stroke-width='1'/>\n",
            padL, padT, padL, padT + chartH));
        s.append(String.format(
            "  <text transform='rotate(-90)' x='%d' y='%d' font-family='Arial' font-size='14' " +
            "text-anchor='middle' fill='#555'>%s</text>\n",
            -(padT + chartH / 2), padL - 55, yLabel));

        for (int i = 0; i < n; i++) {
            double x = padL + i * barGap + (barGap - barW * 2 - 6) / 2;

            double bH = before[i] / maxVal * chartH;
            s.append(String.format(
                "  <rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' fill='#4A90D9' rx='2'/>\n",
                x, padT + chartH - bH, barW, bH));

            double aH = after[i] / maxVal * chartH;
            s.append(String.format(
                "  <rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' fill='#E07B39' rx='2'/>\n",
                x + barW + 6, padT + chartH - aH, barW, aH));

            s.append(String.format(
                "  <text x='%.1f' y='%d' font-family='Arial' font-size='11' text-anchor='middle' fill='#555'>%s</text>\n",
                x + barW + 3, padT + chartH + 16, NAMES[i]));
        }

        s.append(String.format(
            "  <text x='%d' y='%d' font-family='Arial' font-size='14' text-anchor='middle' fill='#555'>Students</text>\n",
            padL + chartW / 2, padT + chartH + 38));

        int totalLegendWidth = 12 + 8 + 115 + 12 + 8 + 105;
        int legX = padL + chartW / 2 - totalLegendWidth / 2;
        int legY = svgH - 25;
        s.append(String.format("  <rect x='%d' y='%d' width='12' height='12' fill='#4A90D9'/>\n", legX, legY));
        s.append(String.format("  <text x='%d' y='%d' font-family='Arial' font-size='11' fill='#333'>Before Study Sync</text>\n", legX + 16, legY + 11));
        s.append(String.format("  <rect x='%d' y='%d' width='12' height='12' fill='#E07B39'/>\n", legX + 143, legY));
        s.append(String.format("  <text x='%d' y='%d' font-family='Arial' font-size='11' fill='#333'>After Study Sync</text>\n", legX + 159, legY + 11));

        s.append("</svg>\n");
        Files.writeString(Paths.get(path), s.toString());
    }
}