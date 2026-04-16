import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public class LateSubmissions {

    private static final String[] NAMES = { "1", "2", "3", "4", "5", "6", "7", "8", "9"};
    private static final double[] DAYS_LATE_BEFORE = { 3, 2, 1, 1, 0, 1, 2, 0, 3};
    private static final double[] DAYS_LATE_AFTER  = { 1, 2, 0, 1, 0, 2, 2, 0, 2};

    public static void main(String[] args) throws IOException {
        generateLateSubmissions("figures/LateSubmissions.svg");
        System.out.println("Figure generated in figures folder");

        Arrays.stream(new File(".").listFiles(f -> f.getName().endsWith(".class")))
            .forEach(File::delete);
    }

    private static void generateLateSubmissions(String path) throws IOException {
        int n = NAMES.length;
        int svgW = 900;
        int svgH = 500;
        int padL = 100;
        int padR = 30;
        int padT = 70;
        int padB = 80;
        int chartW = svgW - padL - padR;
        int chartH = svgH - padT - padB;

        double barW = (double) chartW / n * 0.3;
        double barGap = (double) chartW / n;
        int maxVal = 4;

        StringBuilder s = new StringBuilder();
        s.append(String.format("<svg xmlns='http://www.w3.org/2000/svg' width='%d' height='%d'>\n", svgW, svgH));
        s.append(String.format("  <rect width='%d' height='%d' fill='#f8f9fa'/>\n", svgW, svgH));
        s.append(String.format(
            "  <text x='%d' y='35' font-family='Arial' font-size='15' font-weight='bold' " +
            "text-anchor='middle' fill='#222'>Amount of Late Submissions Before and After Study Sync</text>\n", svgW / 2));
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
            "  <text transform='rotate(-90)' x='%d' y='%d' font-family='Arial' font-size='12' " +
            "text-anchor='middle' fill='#555'>Amount of Submissions</text>\n",
            -(padT + chartH / 2), padL - 55));

        for (int i = 0; i < n; i++) {
            double x = padL + i * barGap + (barGap - barW * 2 - 6) / 2;

            double bH = DAYS_LATE_BEFORE[i] / maxVal * chartH;
            s.append(String.format(
                "  <rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' fill='#4A90D9' rx='2'/>\n",
                x, padT + chartH - bH, barW, bH));

            double aH = DAYS_LATE_AFTER[i] / maxVal * chartH;
            s.append(String.format(
                "  <rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' fill='#E07B39' rx='2'/>\n",
                x + barW + 6, padT + chartH - aH, barW, aH));

            s.append(String.format(
                "  <text x='%.1f' y='%d' font-family='Arial' font-size='10' text-anchor='middle' fill='#555'>%s</text>\n",
                x + barW + 3, padT + chartH + 18, NAMES[i]));
        }

        s.append(String.format(
            "  <text x='%d' y='%d' font-family='Arial' font-size='11' text-anchor='middle' fill='#555'>Students</text>\n",
            padL + chartW / 2, padT + chartH + 40));

        int legX = padL + chartW / 2 - 130;
        int legY = svgH - 25;
        s.append(String.format("  <rect x='%d' y='%d' width='12' height='12' fill='#4A90D9'/>\n", legX, legY));
        s.append(String.format("  <text x='%d' y='%d' font-family='Arial' font-size='11' fill='#333'>Before Study Sync</text>\n", legX + 16, legY + 11));
        s.append(String.format("  <rect x='%d' y='%d' width='12' height='12' fill='#E07B39'/>\n", legX + 145, legY));
        s.append(String.format("  <text x='%d' y='%d' font-family='Arial' font-size='11' fill='#333'>After Study Sync</text>\n", legX + 161, legY + 11));

        s.append("</svg>\n");
        Files.writeString(Paths.get(path), s.toString());
    }
}