import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;

public class StudySyncBot {

    public static void main(String[] args) throws Exception {
        String token     = readEnvValue("DISCORD_TOKEN");
        String channelId = readEnvValue("DISCORD_CHANNEL_ID");
        String feedUrl   = readEnvValue("CANVAS_FEED_URL");

        if (token == null || token.isBlank()) {
            System.err.println("Error: DISCORD_TOKEN not found in .env");
            System.exit(1);
        }
        if (channelId == null || channelId.isBlank()) {
            System.err.println("Error: DISCORD_CHANNEL_ID not found in .env");
            System.exit(1);
        }
        if (feedUrl == null || feedUrl.isBlank()) {
            System.err.println("Error: CANVAS_FEED_URL not found in .env");
            System.err.println("Run CanvasSetup first.");
            System.exit(1);
        }

        JDA jda = JDABuilder.createDefault(token).build();
        jda.awaitReady();
        System.out.println("StudySync bot is online!");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) {
                    System.err.println("Error: Channel not found. Check your DISCORD_CHANNEL_ID in .env");
                    return;
                }

                String icalData = CanvasViewer.fetchFeed(feedUrl);
                List<CanvasViewer.Assignment> assignments = CanvasViewer.parseAssignments(icalData);

                if (assignments.isEmpty()) {
                    channel.sendMessage("No upcoming assignments found!").queue();
                    return;
                }

                // Sort by due date, soonest first
                assignments.sort(Comparator.comparing(a -> a.dueDate != null ? a.dueDate : LocalDateTime.MAX));

                // Get the most upcoming assignment
                CanvasViewer.Assignment next = assignments.get(0);
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a");

                String dueStr  = next.dueDate != null ? next.dueDate.format(fmt) : "No due date";
                String urgency = getUrgencyTag(next.dueDate);

                String message = "📚 **Upcoming Assignment**\n" +
                        "**" + (next.title != null ? next.title : "Untitled") + "**\n" +
                        "📅 Due: " + dueStr + "\n" +
                        (urgency.isEmpty() ? "" : "⚠️ " + urgency + "\n") +
                        (next.description != null && !next.description.isBlank()
                                ? "📝 " + truncate(next.description, 100)
                                : "");

                channel.sendMessage(message).queue();
                System.out.println("Posted assignment update: " + next.title);

            } catch (Exception e) {
                System.err.println("Error fetching assignments: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.HOURS); // posts immediately, then every hour
    }

    static String getUrgencyTag(LocalDateTime due) {
        if (due == null) return "";
        long days = Duration.between(LocalDateTime.now(), due).toDays();
        if (due.isBefore(LocalDateTime.now())) return "OVERDUE!";
        if (days == 0) return "Due today!";
        if (days == 1) return "Due tomorrow!";
        if (days <= 7) return "Due this week";
        return "";
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "...";
    }

    static String readEnvValue(String key) throws IOException {
        Path path = Paths.get(".env");
        if (!Files.exists(path)) return null;
        for (String line : Files.readAllLines(path)) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }
}