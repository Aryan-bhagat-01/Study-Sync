import net.dv8tion.jda.api.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
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

    public static final Map<String, ServerConfig> serverConfigs = new HashMap<>();
    public static final String DATA_FILE = "server_data.properties";

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
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES)
                .addEventListeners(new StudySyncBot())
                .build();

        jda.awaitReady();

        jda.updateCommands().addCommands(
                Commands.slash("setup", "Link your Canvas iCal feed URL")
                        .addOption(OptionType.STRING, "url", "Your Canvas iCal feed URL (.ics)", true),
                Commands.slash("unlink", "Remove your linked Canvas iCal feed"),
                Commands.slash("assignments", "Show upcoming assignments")
                        .addOption(OptionType.INTEGER, "count", "How many assignments to show (default: 10)", false),
                Commands.slash("today", "Show assignments due today"),
                Commands.slash("upcoming", "Show assignments due this week"),
                Commands.slash("overdue", "Show overdue assignments"),
                Commands.slash("complete", "Mark an assignment as complete so it won't show up again")
                        .addOption(OptionType.INTEGER, "number", "Assignment number to mark complete", true),
                Commands.slash("delete", "Hide an assignment by number")
                        .addOption(OptionType.INTEGER, "number", "Assignment number to hide", true),
                Commands.slash("unhide", "Restore all hidden and completed assignments"),
                Commands.slash("frequency", "Change how often the bot posts assignments (in hours)")
                        .addOption(OptionType.INTEGER, "hours", "Hours between posts (1-168)", true)
        ).queue();

        System.out.println("StudySync bot is online!");

        for (Map.Entry<String, ServerConfig> entry : serverConfigs.entrySet()) {
            ServerConfig config = entry.getValue();
            if (config.feedUrl != null && !config.feedUrl.isBlank()) {
                if (config.isUserInstall && config.userId != null) {
                    startUserScheduler(entry.getKey(), config.userId, config.frequencyHours);
                } else if (config.channelId != null) {
                    startScheduler(entry.getKey(), config.channelId, config.frequencyHours);
                }
            }
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        boolean isUserInstall = event.getGuild() == null;
        String configKey;
        String userId = event.getUser().getId();

        if (isUserInstall) {
            configKey = "user_" + userId;
        } else {
            configKey = "guild_" + event.getGuild().getId();
        }

        ServerConfig config = getOrCreateConfig(configKey);
        config.isUserInstall = isUserInstall;
        if (isUserInstall) config.userId = userId;

        String channelId = isUserInstall ? null : event.getChannel().getId();

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

                event.reply("Verifying your Canvas feed...").setEphemeral(isUserInstall).queue();
                final String finalUrl = url;
                final String finalChannelId = channelId;
                try {
                    String icalData = CanvasViewer.fetchFeed(finalUrl);
                    if (!icalData.contains("BEGIN:VCALENDAR")) {
                        event.getHook().editOriginal("That URL didn't return a valid iCal feed. Make sure you copied the full link from Canvas Calendar.").queue();
                        return;
                    }
                    int count = countOccurrences(icalData, "BEGIN:VEVENT");
                    config.feedUrl = finalUrl;
                    if (!isUserInstall) config.channelId = finalChannelId;
                    saveAllServerData();

                    String destination = isUserInstall ? "your DMs" : "this channel";
                    event.getHook().editOriginal(
                        "✅ **Feed linked!** Found **" + count + "** event(s).\n" +
                        "I'll post your most upcoming assignment to " + destination + " every **" + config.frequencyHours + "** hour(s).\n\n" +
                        "Use `/assignments` to see all upcoming assignments anytime!"
                    ).queue();

                    if (isUserInstall) {
                        startUserScheduler(configKey, userId, config.frequencyHours);
                    } else {
                        startScheduler(configKey, finalChannelId, config.frequencyHours);
                    }
                } catch (Exception e) {
                    event.getHook().editOriginal("Could not reach that URL. Make sure it's correct and try again.").queue();
                }
            }

            case "unlink" -> {
                if (config.feedUrl == null || config.feedUrl.isBlank()) {
                    event.reply("No Canvas feed is linked. Use `/setup <url>` to link one.").setEphemeral(true).queue();
                    return;
                }
                config.feedUrl = null;
                config.channelId = null;
                config.hiddenAssignments.clear();
                saveAllServerData();

                ScheduledFuture<?> task = tasks.remove(configKey);
                if (task != null) task.cancel(false);
                event.reply("Canvas feed unlinked. The bot will stop posting assignments.").setEphemeral(isUserInstall).queue();
            }

            case "assignments" -> {
                OptionMapping countOption = event.getOption("count");
                int limit = countOption != null ? (int) countOption.getAsLong() : 10;
                if (limit < 1) limit = 1;
                if (limit > 50) limit = 50;

                event.reply("Fetching your assignments...").setEphemeral(isUserInstall).queue();
                try {
                    List<CanvasViewer.Assignment> assignments = getUpcomingAssignments(config);
                    if (assignments == null) {
                        event.getHook().editOriginal("No Canvas feed linked yet. Use `/setup <url>` to link one.").queue();
                        return;
                    }
                    if (assignments.isEmpty()) {
                        event.getHook().editOriginal("No upcoming assignments found!").queue();
                        return;
                    }
                    List<CanvasViewer.Assignment> limited = assignments.subList(0, Math.min(limit, assignments.size()));
                    String header = "📚 **Upcoming Assignments** (showing " + limited.size() + " of " + assignments.size() + ")";
                    event.getHook().editOriginal(buildAssignmentList(limited, header)).queue();
                } catch (Exception e) {
                    event.getHook().editOriginal("Error fetching assignments: " + e.getMessage()).queue();
                }
            }

            case "today" -> {
                event.reply("Checking what's due today...").setEphemeral(isUserInstall).queue();
                try {
                    List<CanvasViewer.Assignment> assignments = getUpcomingAssignments(config);
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

            case "upcoming" -> {
                event.reply("Checking what's due this week...").setEphemeral(isUserInstall).queue();
                try {
                    List<CanvasViewer.Assignment> assignments = getUpcomingAssignments(config);
                    if (assignments == null) {
                        event.getHook().editOriginal("No Canvas feed linked yet. Use `/setup <url>` to link one.").queue();
                        return;
                    }
                    LocalDateTime now = LocalDateTime.now();
                    LocalDateTime weekEnd = now.plusDays(7);
                    List<CanvasViewer.Assignment> thisWeek = assignments.stream()
                            .filter(a -> a.dueDate != null && a.dueDate.isAfter(now) && a.dueDate.isBefore(weekEnd))
                            .collect(Collectors.toList());
                    if (thisWeek.isEmpty()) {
                        event.getHook().editOriginal("Nothing due this week! \uD83C\uDF89").queue();
                        return;
                    }
                    event.getHook().editOriginal(buildAssignmentList(thisWeek, "\uD83D\uDCC5 **Due This Week**")).queue();
                } catch (Exception e) {
                    event.getHook().editOriginal("Error fetching assignments: " + e.getMessage()).queue();
                }
            }

            case "overdue" -> {
                event.reply("Checking overdue assignments...").setEphemeral(isUserInstall).queue();
                try {
                    List<CanvasViewer.Assignment> assignments = getVisibleAssignments(config);
                    if (assignments == null) {
                        event.getHook().editOriginal("No Canvas feed linked yet. Use `/setup <url>` to link one.").queue();
                        return;
                    }
                    LocalDateTime now = LocalDateTime.now();
                    List<CanvasViewer.Assignment> overdue = assignments.stream()
                            .filter(a -> a.dueDate != null && a.dueDate.isBefore(now))
                            .collect(Collectors.toList());
                    if (overdue.isEmpty()) {
                        event.getHook().editOriginal("No overdue assignments! \uD83C\uDF89").queue();
                        return;
                    }
                    event.getHook().editOriginal(buildAssignmentList(overdue, "⚠️ **Overdue Assignments**")).queue();
                } catch (Exception e) {
                    event.getHook().editOriginal("Error fetching assignments: " + e.getMessage()).queue();
                }
            }

            case "complete" -> {
                int number = (int) event.getOption("number").getAsLong();
                try {
                    List<CanvasViewer.Assignment> assignments = getUpcomingAssignments(config);
                    if (assignments == null || assignments.isEmpty()) {
                        event.reply("No assignments found.").setEphemeral(true).queue();
                        return;
                    }
                    if (number < 1 || number > assignments.size()) {
                        event.reply("Invalid number. Use `/assignments` to see the list.").setEphemeral(true).queue();
                        return;
                    }
                    String title = assignments.get(number - 1).title;
                    config.hiddenAssignments.add(title);
                    saveAllServerData();
                    event.reply("✅ Marked **" + title + "** as complete! It won't show up again.\nUse `/unhide` to restore it if needed.").setEphemeral(isUserInstall).queue();
                } catch (Exception e) {
                    event.reply("Error marking assignment as complete: " + e.getMessage()).setEphemeral(true).queue();
                }
            }

            case "delete" -> {
                int number = (int) event.getOption("number").getAsLong();
                try {
                    List<CanvasViewer.Assignment> assignments = getUpcomingAssignments(config);
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
                    event.reply("Hidden **" + title + "**. Use `/unhide` to restore it.").setEphemeral(isUserInstall).queue();
                } catch (Exception e) {
                    event.reply("Error hiding assignment: " + e.getMessage()).setEphemeral(true).queue();
                }
            }

            case "unhide" -> {
                config.hiddenAssignments.clear();
                saveAllServerData();
                event.reply("All hidden and completed assignments have been restored!").setEphemeral(isUserInstall).queue();
            }

            case "frequency" -> {
                int hours = (int) event.getOption("hours").getAsLong();
                if (hours < 1 || hours > 168) {
                    event.reply("Please enter a value between 1 and 168 hours.").setEphemeral(true).queue();
                    return;
                }
                config.frequencyHours = hours;
                saveAllServerData();
                if (isUserInstall) {
                    startUserScheduler(configKey, userId, hours);
                } else if (config.channelId != null) {
                    startScheduler(configKey, config.channelId, hours);
                }
                event.reply("Got it! I'll post assignments every **" + hours + "** hour(s).").setEphemeral(isUserInstall).queue();
            }
        }
    }

    // Guild install — posts to a server channel
    static void startScheduler(String configKey, String channelId, int frequencyHours) {
        ScheduledFuture<?> existing = tasks.get(configKey);
        if (existing != null) existing.cancel(false);

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                ServerConfig config = serverConfigs.get(configKey);
                if (config == null || config.feedUrl == null || config.feedUrl.isBlank()) return;

                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) { System.err.println("Channel not found: " + channelId); return; }

                List<CanvasViewer.Assignment> assignments = getUpcomingAssignments(config);
                if (assignments == null || assignments.isEmpty()) {
                    channel.sendMessage("No upcoming assignments!").queue();
                    return;
                }

                channel.sendMessage(buildNextAssignmentMessage(assignments.get(0))).queue();
                System.out.println("[" + configKey + "] Posted: " + assignments.get(0).title);

            } catch (Exception e) {
                System.err.println("Error posting for " + configKey + ": " + e.getMessage());
            }
        }, 0, frequencyHours, TimeUnit.HOURS);

        tasks.put(configKey, task);
    }

    // User install — sends a DM to the user
    static void startUserScheduler(String configKey, String userId, int frequencyHours) {
        ScheduledFuture<?> existing = tasks.get(configKey);
        if (existing != null) existing.cancel(false);

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                ServerConfig config = serverConfigs.get(configKey);
                if (config == null || config.feedUrl == null || config.feedUrl.isBlank()) return;

                List<CanvasViewer.Assignment> assignments = getUpcomingAssignments(config);
                if (assignments == null || assignments.isEmpty()) return;

                jda.retrieveUserById(userId).queue(user -> {
                    user.openPrivateChannel().queue(channel -> {
                        channel.sendMessage(buildNextAssignmentMessage(assignments.get(0))).queue();
                        System.out.println("[DM:" + userId + "] Posted: " + assignments.get(0).title);
                    });
                });

            } catch (Exception e) {
                System.err.println("Error DMing user " + userId + ": " + e.getMessage());
            }
        }, 0, frequencyHours, TimeUnit.HOURS);

        tasks.put(configKey, task);
    }

    static String buildNextAssignmentMessage(CanvasViewer.Assignment next) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a");
        String dueStr  = next.dueDate != null ? next.dueDate.format(fmt) : "No due date";
        String urgency = getUrgencyTag(next.dueDate);
        return "\uD83D\uDCDA **Upcoming Assignment**\n" +
                "**" + (next.title != null ? next.title : "Untitled") + "**\n" +
                "\uD83D\uDCC5 Due: " + dueStr + "\n" +
                (urgency.isEmpty() ? "" : "⚠️ " + urgency);
    }

    static List<CanvasViewer.Assignment> getVisibleAssignments(ServerConfig config) throws Exception {
        if (config.feedUrl == null || config.feedUrl.isBlank()) return null;
        String icalData = CanvasViewer.fetchFeed(config.feedUrl);
        List<CanvasViewer.Assignment> assignments = CanvasViewer.parseAssignments(icalData);
        assignments.removeIf(a -> a.title != null && config.hiddenAssignments.contains(a.title));
        assignments.sort(Comparator.comparing(a -> a.dueDate != null ? a.dueDate : LocalDateTime.MAX));
        return assignments;
    }

    static List<CanvasViewer.Assignment> getUpcomingAssignments(ServerConfig config) throws Exception {
        List<CanvasViewer.Assignment> assignments = getVisibleAssignments(config);
        if (assignments == null) return null;
        LocalDateTime now = LocalDateTime.now();
        assignments.removeIf(a -> a.dueDate != null && a.dueDate.isBefore(now));
        return assignments;
    }

    static String buildAssignmentList(List<CanvasViewer.Assignment> assignments, String header) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' hh:mm a");
        StringBuilder sb = new StringBuilder(header + "\n");
        sb.append("─────────────────────────────────\n");
        int num = 1;
        for (CanvasViewer.Assignment a : assignments) {
            String title  = a.title != null ? a.title : "Untitled";
            String due    = a.dueDate != null ? a.dueDate.format(fmt) : "No due date";
            String urgent = getUrgencyTag(a.dueDate);
            sb.append("**").append(num++).append(". ").append(title).append("**\n");
            sb.append("\uD83D\uDCC5 ").append(due);
            if (!urgent.isEmpty()) sb.append("  ⚠️ ").append(urgent);
            sb.append("\n");
            sb.append("─────────────────────────────────\n");
            if (sb.length() > 1800) { sb.append("*(and more...)*\n"); break; }
        }
        return sb.toString();
    }

    static class ServerConfig {
        String feedUrl          = null;
        String channelId        = null;
        String userId           = null;
        boolean isUserInstall   = false;
        int frequencyHours      = 1;
        Set<String> hiddenAssignments = new HashSet<>();
    }

    static ServerConfig getOrCreateConfig(String key) {
        return serverConfigs.computeIfAbsent(key, k -> new ServerConfig());
    }

    static void saveAllServerData() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, ServerConfig> entry : serverConfigs.entrySet()) {
                String id = entry.getKey();
                ServerConfig c = entry.getValue();
                sb.append(id).append(".feedUrl=").append(c.feedUrl != null ? c.feedUrl : "").append("\n");
                sb.append(id).append(".channelId=").append(c.channelId != null ? c.channelId : "").append("\n");
                sb.append(id).append(".userId=").append(c.userId != null ? c.userId : "").append("\n");
                sb.append(id).append(".isUserInstall=").append(c.isUserInstall).append("\n");
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
                String configKey = key.substring(0, dot);
                String field     = key.substring(dot + 1);
                ServerConfig config = getOrCreateConfig(configKey);
                switch (field) {
                    case "feedUrl"        -> config.feedUrl        = value.isBlank() ? null : value;
                    case "channelId"      -> config.channelId      = value.isBlank() ? null : value;
                    case "userId"         -> config.userId         = value.isBlank() ? null : value;
                    case "isUserInstall"  -> config.isUserInstall  = Boolean.parseBoolean(value);
                    case "frequencyHours" -> config.frequencyHours = value.isBlank() ? 1 : Integer.parseInt(value);
                    case "hidden"         -> { if (!value.isBlank()) config.hiddenAssignments.addAll(Arrays.asList(value.split(","))); }
                }
            }
            System.out.println("Loaded data for " + serverConfigs.size() + " config(s).");
        } catch (IOException e) {
            System.err.println("Error loading server data: " + e.getMessage());
        }
    }

   static String getUrgencyTag(LocalDateTime due) {
    if (due == null) return "🟢";
    long days = Duration.between(LocalDateTime.now(), due).toDays();
    if (due.isBefore(LocalDateTime.now())) return "🔴 OVERDUE!";
    if (days == 0) return "🔴 Due today!";
    if (days == 1) return "🟡 Due tomorrow!";
    if (days <= 7) return "🟡 Due this week";
    return "🟢 Upcoming";
    }

    static int countOccurrences(String text, String target) {
        int count = 0, index = 0;
        while ((index = text.indexOf(target, index)) != -1) { count++; index += target.length(); }
        return count;
    }
}