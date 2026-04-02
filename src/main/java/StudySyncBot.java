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

    private static final Map<String, ServerConfig> serverConfigs = new HashMap<>();
    private static final String DATA_FILE = "server_data.properties";

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private static final Map<String, ScheduledFuture<?>> tasks = new HashMap<>();
    private static JDA jda;

    public static void main(String[] args) throws Exception {
        String token = System.getenv("DISCORD_TOKEN");

        if (token == null || token.isBlank()) {
            System.err.println("Error: DISCORD_TOKEN environment variable not set.");
            System.exit(1);
        }

        loadAllServerData();

        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES)
                .addEventListeners(new StudySyncBot())
                .build();

        jda.awaitReady();

        jda.updateCommands().addCommands(
                Commands.slash("setup", "Link your Canvas iCal feed URL to this server")
                        .addOption(OptionType.STRING, "url", "Your Canvas iCal feed URL (.ics)", true),
                Commands.slash("unlink", "Remove this server's linked Canvas iCal feed"),
                Commands.slash("assignments", "Show all upcoming assignments"),
                Commands.slash("today", "Show assignments due today"),
                Commands.slash("edit", "Hide an assignment by number (use /assignments to see numbers)")
                        .addOption(OptionType.INTEGER, "number", "Assignment number to hide", true),
                Commands.slash("unhide", "Restore all hidden assignments"),
                Commands.slash("frequency", "Change how often the bot posts assignments (in hours)")
                        .addOption(OptionType.INTEGER, "hours", "Hours between posts (1-168)", true)
        ).queue();

        System.out.println("StudySync bot is online!");

        for (Map.Entry<String, ServerConfig> entry : serverConfigs.entrySet()) {
            ServerConfig config = entry.getValue();
            if (config.feedUrl != null && !config.feedUrl.isBlank() && config.channelId != null) {
                startScheduler(entry.getKey(), config.channelId, config.frequencyHours);
            }
        }
    }


    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            event.reply("This bot only works in servers.").setEphemeral(true).queue();
            return;
        }

        String guildId   = event.getGuild().getId();
        String channelId = event.getChannel().getId();
        ServerConfig config = getOrCreateConfig(guildId);

        switch (event.getName()) {

            case "setup" -> {
                String url = event.getOption("url").getAsString().trim();
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;

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

                    config.feedUrl   = finalUrl;
                    config.channelId = channelId;
                    saveAllServerData();

                    event.getHook().editOriginal("Feed linked! Found **" + count + "** event(s). I'll post updates in this channel every **" + config.frequencyHours + "** hour(s).").queue();
                    startScheduler(guildId, channelId, config.frequencyHours);

                } catch (Exception e) {
                    event.getHook().editOriginal("Could not reach that URL. Make sure it's correct and try again.").queue();
                }
            }

            case "unlink" -> {
                if (config.feedUrl == null || config.feedUrl.isBlank()) {
                    event.reply("No Canvas feed is linked to this server.").setEphemeral(true).queue();
                    return;
                }
                // Clear only this server's data
                config.feedUrl   = null;
                config.channelId = null;
                config.hiddenAssignments.clear();
                saveAllServerData();

                // Stop this server's scheduler
                ScheduledFuture<?> task = tasks.remove(guildId);
                if (task != null) task.cancel(false);

                event.reply("Canvas feed unlinked for this server. The bot will stop posting assignments.").queue();
            }

            case "assignments" -> {
                event.reply("Fetching your assignments...").queue();
                try {
                    List<CanvasViewer.Assignment> assignments = getVisibleAssignments(config);
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
                    List<CanvasViewer.Assignment> assignments = getVisibleAssignments(config);
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
                    List<CanvasViewer.Assignment> assignments = getVisibleAssignments(config);
                    if (assignments == null || assignments.isEmpty()) {
                        event.reply("No assignments to hide.").setEphemeral(true).queue();
                        return;
                    }
                    if (number < 1 || number > assignments.size()) {
                        event.reply("Invalid number. Use `/assignments` to see the list.").setEphemeral(true).queue();
                        return;
                    }
                    String title = assignments.get(number - 1).title;
                    config.hiddenAssignments.add(title);
                    saveAllServerData();
                    event.reply("Hidden **" + title + "**. Use `/unhide` to restore it.").queue();
                } catch (Exception e) {
                    event.reply("Error hiding assignment: " + e.getMessage()).setEphemeral(true).queue();
                }
            }

            case "unhide" -> {
                config.hiddenAssignments.clear();
                saveAllServerData();
                event.reply("All hidden assignments have been restored!").queue();
            }

            case "frequency" -> {
                int hours = (int) event.getOption("hours").getAsLong();
                if (hours < 1 || hours > 168) {
                    event.reply("Please enter a value between 1 and 168 hours.").setEphemeral(true).queue();
                    return;
                }
                config.frequencyHours = hours;
                saveAllServerData();
                if (config.channelId != null) startScheduler(guildId, config.channelId, hours);
                event.reply("Got it! I'll post assignments every **" + hours + "** hour(s).").queue();
            }
        }
    }


    static void startScheduler(String guildId, String channelId, int frequencyHours) {
        ScheduledFuture<?> existing = tasks.get(guildId);
        if (existing != null) existing.cancel(false);

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                ServerConfig config = serverConfigs.get(guildId);
                if (config == null || config.feedUrl == null || config.feedUrl.isBlank()) return;

                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) { System.err.println("Channel not found for guild " + guildId); return; }

                List<CanvasViewer.Assignment> assignments = getVisibleAssignments(config);
                if (assignments == null || assignments.isEmpty()) {
                    channel.sendMessage("No upcoming assignments!").queue();
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
                System.out.println("[" + guildId + "] Posted: " + next.title);

            } catch (Exception e) {
                System.err.println("Error posting for guild " + guildId + ": " + e.getMessage());
            }
        }, 0, frequencyHours, TimeUnit.HOURS);

        tasks.put(guildId, task);
    }


    static class ServerConfig {
        String feedUrl      = null;
        String channelId    = null;
        int frequencyHours  = 1;
        Set<String> hiddenAssignments = new HashSet<>();
    }

    static ServerConfig getOrCreateConfig(String guildId) {
        return serverConfigs.computeIfAbsent(guildId, k -> new ServerConfig());
    }

    static void saveAllServerData() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, ServerConfig> entry : serverConfigs.entrySet()) {
                String id = entry.getKey();
                ServerConfig c = entry.getValue();
                sb.append(id).append(".feedUrl=").append(c.feedUrl != null ? c.feedUrl : "").append("\n");
                sb.append(id).append(".channelId=").append(c.channelId != null ? c.channelId : "").append("\n");
                sb.append(id).append(".frequencyHours=").append(c.frequencyHours).append("\n");
                sb.append(id).append(".hidden=").append(String.join(",", c.hiddenAssignments)).append("\n");
            }
            Files.writeString(Paths.get(DATA_FILE), sb.toString());
        } catch (IOException e) {
            System.err.println("Error saving server data: " + e.getMessage());
        }
    }

    static void loadAllServerData() {
        try {
            Path path = Paths.get(DATA_FILE);
            if (!Files.exists(path)) return;
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank()) continue;
                int eq = line.indexOf('=');
                if (eq == -1) continue;
                String key   = line.substring(0, eq);
                String value = line.substring(eq + 1).trim();
                int dot = key.lastIndexOf('.');
                if (dot == -1) continue;
                String guildId = key.substring(0, dot);
                String field   = key.substring(dot + 1);
                ServerConfig config = getOrCreateConfig(guildId);
                switch (field) {
                    case "feedUrl"       -> config.feedUrl      = value.isBlank() ? null : value;
                    case "channelId"     -> config.channelId    = value.isBlank() ? null : value;
                    case "frequencyHours"-> config.frequencyHours = value.isBlank() ? 1 : Integer.parseInt(value);
                    case "hidden"        -> {
                        if (!value.isBlank()) {
                            config.hiddenAssignments.addAll(Arrays.asList(value.split(",")));
                        }
                    }
                }
            }
            System.out.println("Loaded data for " + serverConfigs.size() + " server(s).");
        } catch (IOException e) {
            System.err.println("Error loading server data: " + e.getMessage());
        }
    }



    static List<CanvasViewer.Assignment> getVisibleAssignments(ServerConfig config) throws Exception {
        if (config.feedUrl == null || config.feedUrl.isBlank()) return null;
        String icalData = CanvasViewer.fetchFeed(config.feedUrl);
        List<CanvasViewer.Assignment> assignments = CanvasViewer.parseAssignments(icalData);
        assignments.removeIf(a -> a.title != null && config.hiddenAssignments.contains(a.title));
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
            if (sb.length() > 1800) { sb.append("*(and more...)*"); break; }
        }
        return sb.toString();
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

    static int countOccurrences(String text, String target) {
        int count = 0, index = 0;
        while ((index = text.indexOf(target, index)) != -1) { count++; index += target.length(); }
        return count;
    }
}