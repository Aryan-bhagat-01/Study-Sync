import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class StudySyncBot extends ListenerAdapter {

    private static final String HIDDEN_FILE = ".hidden_assignments";

    private static ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> currentTask;
    private static JDA jda;

    public static void main(String[] args) throws Exception {
        String token     = readEnvValue("DISCORD_TOKEN");
        String channelId = readEnvValue("DISCORD_CHANNEL_ID");

        if (token == null || token.isBlank()) {
            System.err.println("Error: DISCORD_TOKEN not found in .env");
            System.exit(1);
        }
        if (channelId == null || channelId.isBlank()) {
            System.err.println("Error: DISCORD_CHANNEL_ID not found in .env");
            System.exit(1);
        }

        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES)
                .addEventListeners(new StudySyncBot())
                .build();

        jda.awaitReady();

        // Register slash commands
        jda.updateCommands().addCommands(
                Commands.slash("setup", "Link your Canvas iCal feed URL")
                        .addOption(OptionType.STRING, "url", "Your Canvas iCal feed URL (.ics)", true),
                Commands.slash("unlink", "Remove your linked Canvas iCal feed"),
                Commands.slash("assignments", "Show all upcoming assignments"),
                Commands.slash("today", "Show assignments due today"),
                Commands.slash("edit", "Hide an assignment from the bot by number (use /assignments to see numbers)")
                        .addOption(OptionType.INTEGER, "number", "Assignment number to hide", true),
                Commands.slash("unhide", "Restore all hidden assignments"),
                Commands.slash("frequency", "Change how often the bot posts assignments (in hours)")
                        .addOption(OptionType.INTEGER, "hours", "Number of hours between posts (e.g. 1, 2, 6, 12, 24)", true)
        ).queue();

        System.out.println("StudySync bot is online!");

        int frequency = getFrequency();
        startScheduler(channelId, frequency);
    }

    // ── Slash Command Handler ─────────────────────────────────────────────────

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String channelId;
        try {
            channelId = readEnvValue("DISCORD_CHANNEL_ID");
        } catch (IOException e) {
            event.reply("Error reading config.").setEphemeral(true).queue();
            return;
        }

        switch (event.getName()) {

            case "setup" -> {
                String url = event.getOption("url").getAsString().trim();

                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }

                String path;
                try {
                    path = new java.net.URI(url).getPath();
                } catch (Exception e) {
                    event.reply("That doesn't look like a valid URL.").setEphemeral(true).queue();
                    return;
                }

                if (!path.endsWith(".ics")) {
                    event.reply("That doesn't look like a Canvas iCal URL — it should end in `.ics`.").setEphemeral(true).queue();
                    return;
                }

                event.reply("Verifying your Canvas feed...").queue();

                final String finalUrl = url;
                try {
                    String icalData = CanvasViewer.fetchFeed(finalUrl);
                    if (!icalData.contains("BEGIN:VCALENDAR")) {
                        event.getHook().editOriginal("That URL didn't return a valid iCal feed. Make sure you copied the full link from Canvas Calendar.").queue();
                        return;
                    }

                    int count = countOccurrences(icalData, "BEGIN:VEVENT");
                    writeEnvValue("CANVAS_FEED_URL", finalUrl);
                    event.getHook().editOriginal("Feed linked! Found **" + count + "** event(s). The bot will now post assignments every hour.").queue();

                    int frequency = getFrequency();
                    startScheduler(channelId, frequency);

                } catch (Exception e) {
                    event.getHook().editOriginal("Could not reach that URL. Make sure it's correct and try again.").queue();
                }
            }

            case "unlink" -> {
                try {
                    removeEnvValue("CANVAS_FEED_URL");
                    if (currentTask != null) currentTask.cancel(false);
                    event.reply("Canvas feed unlinked. The bot will stop posting assignments.").queue();
                } catch (IOException e) {
                    event.reply("Error unlinking feed.").setEphemeral(true).queue();
                }
            }

            case "assignments" -> {
                event.reply("Fetching your assignments...").queue();
                try {
                    List<CanvasViewer.Assignment> assignments = getVisibleAssignments();
                    if (assignments == null) {
                        event.getHook().editOriginal("No Canvas feed linked yet. Use `/setup <url>` to link one.").queue();
                        return;
                    }
                    if (assignments.isEmpty()) {
                        event.getHook().editOriginal("No upcoming assignments found!").queue();
                        return;
                    }
                    event.getHook().editOriginal(buildAssignmentList(assignments, "📚 **Upcoming Assignments**")).queue();
                } catch (Exception e) {
                    event.getHook().editOriginal("Error fetching assignments: " + e.getMessage()).queue();
                }
            }

            case "today" -> {
                event.reply("Checking what's due today...").queue();
                try {
                    List<CanvasViewer.Assignment> assignments = getVisibleAssignments();
                    if (assignments == null) {
                        event.getHook().editOriginal("No Canvas feed linked yet. Use `/setup <url>` to link one.").queue();
                        return;
                    }

                    LocalDate today = LocalDate.now();
                    List<CanvasViewer.Assignment> dueToday = assignments.stream()
                            .filter(a -> a.dueDate != null && a.dueDate.toLocalDate().equals(today))
                            .collect(Collectors.toList());

                    if (dueToday.isEmpty()) {
                        event.getHook().editOriginal("Nothing due today! \uD83C\uDF89").queue();
                        return;
                    }

                    event.getHook().editOriginal(buildAssignmentList(dueToday, "\uD83D\uDCC5 **Due Today**")).queue();

                } catch (Exception e) {
                    event.getHook().editOriginal("Error fetching assignments: " + e.getMessage()).queue();
                }
            }

            case "edit" -> {
                int number = (int) event.getOption("number").getAsLong();
                try {
                    List<CanvasViewer.Assignment> assignments = getVisibleAssignments();
                    if (assignments == null || assignments.isEmpty()) {
                        event.reply("No assignments to hide.").setEphemeral(true).queue();
                        return;
                    }

                    if (number < 1 || number > assignments.size()) {
                        event.reply("Invalid number. Use `/assignments` to see the list and pick a valid number.").setEphemeral(true).queue();
                        return;
                    }

                    CanvasViewer.Assignment toHide = assignments.get(number - 1);
                    hideAssignment(toHide.title);
                    event.reply("Hidden **" + toHide.title + "** from the bot. Use `/unhide` to restore all hidden assignments.").queue();

                } catch (Exception e) {
                    event.reply("Error hiding assignment: " + e.getMessage()).setEphemeral(true).queue();
                }
            }

            case "unhide" -> {
                try {
                    Path path = Paths.get(HIDDEN_FILE);
                    Files.deleteIfExists(path);
                    event.reply("All hidden assignments have been restored!").queue();
                } catch (IOException e) {
                    event.reply("Error restoring assignments.").setEphemeral(true).queue();
                }
            }

            case "frequency" -> {
                int hours = (int) event.getOption("hours").getAsLong();
                if (hours < 1 || hours > 168) {
                    event.reply("Please enter a value between 1 and 168 hours (1 week).").setEphemeral(true).queue();
                    return;
                }
                try {
                    writeEnvValue("POST_FREQUENCY_HOURS", String.valueOf(hours));
                    startScheduler(channelId, hours);
                    event.reply("Got it! I'll post assignments every **" + hours + "** hour(s).").queue();
                } catch (IOException e) {
                    event.reply("Error saving frequency.").setEphemeral(true).queue();
                }
            }
        }
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    static void startScheduler(String channelId, int frequencyHours) {
        if (currentTask != null) currentTask.cancel(false);

        currentTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                List<CanvasViewer.Assignment> assignments = getVisibleAssignments();
                if (assignments == null || assignments.isEmpty()) return;

                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) {
                    System.err.println("Error: Channel not found.");
                    return;
                }

                CanvasViewer.Assignment next = assignments.get(0);
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a");
                String dueStr  = next.dueDate != null ? next.dueDate.format(fmt) : "No due date";
                String urgency = getUrgencyTag(next.dueDate);

                String message = "\uD83D\uDCDA **Upcoming Assignment**\n" +
                        "**" + (next.title != null ? next.title : "Untitled") + "**\n" +
                        "\uD83D\uDCC5 Due: " + dueStr + "\n" +
                        (urgency.isEmpty() ? "" : "⚠️ " + urgency);

                channel.sendMessage(message).queue();
                System.out.println("Posted assignment update: " + next.title);

            } catch (Exception e) {
                System.err.println("Error posting assignment: " + e.getMessage());
            }
        }, 0, frequencyHours, TimeUnit.HOURS);
    }

    // ── Assignment Helpers ────────────────────────────────────────────────────

    static List<CanvasViewer.Assignment> getVisibleAssignments() throws Exception {
        String feedUrl = readEnvValue("CANVAS_FEED_URL");
        if (feedUrl == null || feedUrl.isBlank()) return null;

        String icalData = CanvasViewer.fetchFeed(feedUrl);
        List<CanvasViewer.Assignment> assignments = CanvasViewer.parseAssignments(icalData);
        Set<String> hidden = loadHiddenAssignments();

        assignments.removeIf(a -> a.title != null && hidden.contains(a.title));
        assignments.sort(Comparator.comparing(a -> a.dueDate != null ? a.dueDate : LocalDateTime.MAX));
        return assignments;
    }

    static String buildAssignmentList(List<CanvasViewer.Assignment> assignments, String header) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a");
        StringBuilder sb = new StringBuilder(header + "\n\n");
        int num = 1;
        for (CanvasViewer.Assignment a : assignments) {
            String title  = a.title != null ? a.title : "Untitled";
            String due    = a.dueDate != null ? a.dueDate.format(fmt) : "No due date";
            String urgent = getUrgencyTag(a.dueDate);

            sb.append("**").append(num++).append(". ").append(title).append("**\n");
            sb.append("\uD83D\uDCC5 ").append(due);
            if (!urgent.isEmpty()) sb.append("  ⚠️ ").append(urgent);
            sb.append("\n\n");

            if (sb.length() > 1800) {
                sb.append("*(and more...)*");
                break;
            }
        }
        return sb.toString();
    }

    static void hideAssignment(String title) throws IOException {
        Path path = Paths.get(HIDDEN_FILE);
        List<String> hidden = Files.exists(path) ? new ArrayList<>(Files.readAllLines(path)) : new ArrayList<>();
        if (!hidden.contains(title)) hidden.add(title);
        Files.writeString(path, String.join("\n", hidden) + "\n");
    }

    static Set<String> loadHiddenAssignments() throws IOException {
        Path path = Paths.get(HIDDEN_FILE);
        if (!Files.exists(path)) return new HashSet<>();
        return new HashSet<>(Files.readAllLines(path));
    }

    // ── General Helpers ───────────────────────────────────────────────────────

    static String getUrgencyTag(LocalDateTime due) {
        if (due == null) return "";
        long days = Duration.between(LocalDateTime.now(), due).toDays();
        if (due.isBefore(LocalDateTime.now())) return "OVERDUE!";
        if (days == 0) return "Due today!";
        if (days == 1) return "Due tomorrow!";
        if (days <= 7) return "Due this week";
        return "";
    }

    static int getFrequency() {
        try {
            String val = readEnvValue("POST_FREQUENCY_HOURS");
            if (val != null && !val.isBlank()) return Integer.parseInt(val);
        } catch (Exception ignored) {}
        return 1;
    }

    static int countOccurrences(String text, String target) {
        int count = 0, index = 0;
        while ((index = text.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
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

    static void writeEnvValue(String key, String value) throws IOException {
        Path path = Paths.get(".env");
        List<String> lines = Files.exists(path) ? new ArrayList<>(Files.readAllLines(path)) : new ArrayList<>();
        lines.removeIf(l -> l.startsWith(key + "="));
        lines.add(key + "=" + value);
        Files.writeString(path, String.join("\n", lines) + "\n");
    }

    static void removeEnvValue(String key) throws IOException {
        Path path = Paths.get(".env");
        if (!Files.exists(path)) return;
        List<String> lines = new ArrayList<>(Files.readAllLines(path));
        lines.removeIf(l -> l.startsWith(key + "="));
        Files.writeString(path, String.join("\n", lines) + "\n");
    }
}