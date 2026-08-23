package org.osmium.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.minecraft.server.MinecraftServer;
import org.osmium.OsmiumConfig;

import java.awt.Color;
import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * Discord bot integration — manage the server from Discord.
 *
 * PERMISSION MODEL (escalation-proof):
 *  - First '/osmium setup' records the invoker as OWNER (persisted to
 *    osmium-discord-bot.json). Only runs once; the Discord GUILD OWNER can
 *    always reclaim ownership (verified against live guild metadata —
 *    cannot be faked by any member).
 *  - Owner maps Discord ROLE IDs to levels: viewer < helper < mod < admin.
 *  - Level is resolved LIVE from the invoking member's current roles at
 *    execution time. Removing a Discord role removes access instantly.
 *  - Only owner/guild-owner may modify mappings. Admins cannot escalate —
 *    they have no write access to the permission store whatsoever.
 *  - Every action executes as CONSOLE on the main thread and is audited
 *    (server log + optional audit channel).
 */
public final class OsmiumDiscordBot extends ListenerAdapter {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Discord-Bot");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile JDA jda;

    // ------------------------------------------------------------------
    // Permission store
    // ------------------------------------------------------------------

    public enum Level { NONE(0), VIEWER(1), HELPER(2), MOD(3), ADMIN(4);
        final int rank;
        Level(int r) { rank = r; }
        static Level of(String s) {
            try { return valueOf(s.toUpperCase(Locale.ROOT)); }
            catch (Exception e) { return NONE; }
        }
    }

    private static class StoreData {
        String ownerId = "";      // discord user id of setup owner
        Map<String, String> roles = new LinkedHashMap<>(); // roleId -> LEVEL name
    }

    private static StoreData store = new StoreData();
    private static File storeFile;
    private static final Object STORE_LOCK = new Object();

    private static void loadStore(File serverDir) {
        storeFile = new File(serverDir, "osmium-discord-bot.json");
        if (!storeFile.exists()) return;
        try (var reader = new java.io.InputStreamReader(new java.io.FileInputStream(storeFile), StandardCharsets.UTF_8)) {
            Type t = new com.google.gson.reflect.TypeToken<StoreData>() {}.getType();
            StoreData loaded = GSON.fromJson(reader, t);
            if (loaded != null) store = loaded;
        } catch (Exception e) {
            LOGGER.error("Failed to load osmium-discord-bot.json", e);
        }
    }

    private static void saveStore() {
        synchronized (STORE_LOCK) {
            try (var writer = new java.io.OutputStreamWriter(new java.io.FileOutputStream(storeFile), StandardCharsets.UTF_8)) {
                GSON.toJson(store, writer);
            } catch (Exception e) {
                LOGGER.error("Failed to save osmium-discord-bot.json", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public static void start(File serverDir) {
        if (!OsmiumConfig.discordBotEnabled) return;
        String token = OsmiumConfig.discordBotToken;
        if (token == null || token.isBlank()) {
            LOGGER.error("discord-bot.enabled=true but token is empty — bot NOT started");
            return;
        }
        loadStore(serverDir);
        try {
            jda = JDABuilder.createDefault(token)
                    .disableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_PRESENCES,
                            GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGE_TYPING)
                    .setActivity(net.dv8tion.jda.api.entities.Activity.watching("the server"))
                    .addEventListeners(new OsmiumDiscordBot())
                    .build();
            LOGGER.info("Discord bot starting...");
        } catch (Exception e) {
            LOGGER.error("Discord bot failed to start: {}", e.getMessage());
            jda = null;
        }
    }

    public static void stop() {
        JDA j = jda;
        jda = null;
        if (j != null) {
            try { j.shutdownNow(); } catch (Exception ignored) {}
            LOGGER.info("Discord bot stopped");
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        registerCommands(event.getJDA());
        LOGGER.info("Discord bot ready as {} — {} guild(s)", event.getJDA().getSelfUser().getName(),
                event.getJDA().getGuildCache().size());
    }

    private void registerCommands(JDA jdaApi) {
        List<SlashCommandData> cmds = List.of(
                Commands.slash("osmium", "Setup & permissions (owner)")
                        .addSubcommands(
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("setup", "Become the bot owner (first run only)"),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("perms-list", "List role permission mappings"),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("perm-add", "Map a role to a permission level")
                                        .addOptions(new OptionData(OptionType.ROLE, "role", "Discord role", true))
                                        .addOptions(new OptionData(OptionType.STRING, "level", "Permission level", true)
                                                .addChoice("viewer", "VIEWER")
                                                .addChoice("helper", "HELPER")
                                                .addChoice("mod", "MOD")
                                                .addChoice("admin", "ADMIN")),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("perm-remove", "Remove a role mapping")
                                        .addOption(OptionType.ROLE, "role", "Discord role", true)),

                Commands.slash("status", "Server status (viewer)"),
                Commands.slash("list", "Online players (viewer)"),
                Commands.slash("tps", "Server performance (viewer)"),

                Commands.slash("kick", "Kick a player (helper+)")
                        .addOption(OptionType.STRING, "player", "Player name", true)
                        .addOption(OptionType.STRING, "reason", "Reason", false),

                Commands.slash("ban", "Ban a player (mod+) — asks for confirmation")
                        .addOption(OptionType.STRING, "player", "Player name", true)
                        .addOption(OptionType.STRING, "reason", "Reason", false),

                Commands.slash("pardon", "Unban a player (mod+)")
                        .addOption(OptionType.STRING, "player", "Player name", true),

                Commands.slash("whitelist", "Manage whitelist (mod+)")
                        .addSubcommands(
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("add", "Add player")
                                        .addOption(OptionType.STRING, "player", "Player name", true),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("remove", "Remove player")
                                        .addOption(OptionType.STRING, "player", "Player name", true),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("list", "Show whitelist")),

                Commands.slash("say", "Broadcast as console (admin+)")
                        .addOption(OptionType.STRING, "message", "Message", true),

                Commands.slash("console", "Run a console command (admin+)")
                        .addOption(OptionType.STRING, "command", "Command without leading slash", true)
        );
        jdaApi.updateCommands().addCommands(cmds).queue(
                ok -> LOGGER.info("Registered {} Discord slash commands", cmds.size()),
                err -> LOGGER.error("Failed to register slash commands: {}", err.getMessage()));
    }

    // ------------------------------------------------------------------
    // Permission resolution
    // ------------------------------------------------------------------

    private boolean isOwner(SlashCommandInteractionEvent e) {
        long gid = e.getGuild() != null ? e.getGuild().getIdLong() : -1;
        if (e.getUser().getIdLong() == e.getGuild().getOwnerIdLong()) return true; // real guild owner
        return !store.ownerId.isEmpty() && e.getUser().getId().equals(store.ownerId);
    }

    private boolean isOwner(ButtonInteractionEvent e) {
        if (e.getGuild() == null) return false;
        if (e.getUser().getIdLong() == e.getGuild().getOwnerIdLong()) return true;
        return !store.ownerId.isEmpty() && e.getUser().getId().equals(store.ownerId);
    }

    /** Live role->level resolution from the interacting member's current roles. */
    private Level levelOf(SlashCommandInteractionEvent e) {
        if (isOwner(e)) return Level.ADMIN; // owners implicitly have admin; owner-only cmds checked separately
        if (e.getMember() == null) return Level.NONE;
        Level best = Level.NONE;
        for (var role : e.getMember().getRoles()) {
            String lvlName = store.roles.get(role.getId());
            if (lvlName == null) continue;
            Level l = Level.of(lvlName);
            if (l.rank > best.rank) best = l;
        }
        return best;
    }

    private boolean has(SlashCommandInteractionEvent e, Level required) {
        return levelOf(e).rank >= required.rank;
    }

    // ------------------------------------------------------------------
    // Audit
    // ------------------------------------------------------------------

    private void audit(SlashCommandInteractionEvent e, String action) {
        String line = "[Discord] " + e.getUser().getName() + " (" + e.getUser().getId() + ") -> " + action;
        LOGGER.info(line);
        auditChannel(e.getGuild(), line);
    }

    private void auditChannel(net.dv8tion.jda.api.entities.Guild guild, String text) {
        String chId = OsmiumConfig.discordBotAuditChannelId;
        if (chId == null || chId.isBlank() || guild == null) return;
        TextChannel ch = guild.getTextChannelById(chId.trim());
        if (ch != null) ch.sendMessage("`" + text + "`").queue(ok -> {}, err -> {});
    }

    // ------------------------------------------------------------------
    // Slash commands
    // ------------------------------------------------------------------

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        try {
            handleSlash(e);
        } catch (Exception ex) {
            LOGGER.error("Discord command error", ex);
            replyError(e, "Internal error: " + ex.getMessage());
        }
    }

    private void handleSlash(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) { replyError(e, "Server commands only work inside a guild."); return; }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) { replyError(e, "Server is not running."); return; }

        switch (e.getName()) {
            case "osmium" -> handleOsmiumAdmin(e, server);
            case "status" -> { require(e, Level.VIEWER); replyInfo(e, buildStatus(server)); }
            case "list" -> { require(e, Level.VIEWER); replyInfo(e, buildList(server)); }
            case "tps" -> { require(e, Level.VIEWER); replyInfo(e, buildPerf(server)); }
            case "kick" -> handleKick(e, server);
            case "ban" -> handleBan(e, server);
            case "pardon" -> handlePardon(e, server);
            case "whitelist" -> handleWhitelist(e, server);
            case "say" -> handleSay(e, server);
            case "console" -> handleConsole(e, server);
            default -> replyError(e, "Unknown command.");
        }
    }

    private void require(SlashCommandInteractionEvent e, Level required) {
        if (!has(e, required)) {
            throw new PermissionDeniedException(required);
        }
    }

    private static final class PermissionDeniedException extends RuntimeException {
        PermissionDeniedException(Level required) {
            super("requires " + required);
        }
    }

    private void replyError(SlashCommandInteractionEvent e, String msg) {
        e.replyEmbeds(errorEmbed(msg)).setEphemeral(true).queue(ok -> {}, err -> {});
    }

    private void replyInfo(SlashCommandInteractionEvent e, String msg) {
        MessageEmbed embed = new EmbedBuilder()
                .setDescription(msg.length() > 3900 ? msg.substring(0, 3900) : msg)
                .setColor(new Color(0x2ECC71))
                .setFooter(e.getUser().getName())
                .build();
        e.replyEmbeds(embed).setEphemeral(false).queue(ok -> {}, err -> {});
    }

    private MessageEmbed errorEmbed(String msg) {
        return new EmbedBuilder().setDescription("\u274c " + msg).setColor(new Color(0xE74C3C)).build();
    }

    // ---- /osmium (owner management) ----

    private void handleOsmiumAdmin(SlashCommandInteractionEvent e, MinecraftServer server) {
        var sub = e.getSubcommandName();
        if (sub == null) { replyError(e, "Unknown subcommand."); return; }

        switch (sub) {
            case "setup" -> {
                if (!store.ownerId.isEmpty() && !isOwner(e)) {
                    replyError(e, "Bot already owned by <@" + store.ownerId + ">. The Discord **guild owner** can reclaim with this command.");
                    return;
                }
                store.ownerId = e.getUser().getId();
                if (e.getGuild() != null) {
                    // seed nothing else; roles start empty
                }
                saveStore();
                audit(e, "became bot owner");
                replyInfo(e, "✅ You are now the bot owner.\n\nNext steps:\n" +
                        "1. Create Discord roles for your staff (or reuse existing ones)\n" +
                        "2. `/osmium perm-add role:<role> level:<viewer|helper|mod|admin>`\n" +
                        "3. Members with mapped roles gain matching abilities.");
            }
            case "perms-list" -> {
                if (!isOwner(e)) { replyError(e, "Owner only."); return; }
                if (store.roles.isEmpty()) { replyInfo(e, "No role mappings yet. Use `/osmium perm-add`."); return; }
                StringBuilder sb = new StringBuilder("**Role mappings**\n");
                for (var entry : store.roles.entrySet()) {
                    var role = e.getGuild().getRoleById(entry.getKey());
                    sb.append("• ").append(role != null ? role.getAsMention() : "*(deleted role)* " + entry.getKey())
                      .append(" → **").append(entry.getValue().toLowerCase()).append("**\n");
                }
                replyInfo(e, sb.toString());
            }
            case "perm-add" -> {
                if (!isOwner(e)) { replyError(e, "Owner only."); return; }
                var roleOpt = e.getOption("role");
                var lvlOpt = e.getOption("level");
                if (roleOpt == null || lvlOpt == null) { replyError(e, "Missing options."); return; }
                Level lvl = Level.of(lvlOpt.getAsString());
                if (lvl == Level.NONE) { replyError(e, "Invalid level."); return; }
                String roleId = roleOpt.getAsRole().getId();
                if (roleId.equals(e.getGuild().getId())) { replyError(e, "Can't map @everyone."); return; }
                store.roles.put(roleId, lvl.name());
                saveStore();
                audit(e, "mapped role " + roleId + " -> " + lvl);
                replyInfo(e, "✅ " + roleOpt.getAsRole().getAsMention() + " → **" + lvl.name().toLowerCase() + "**\n" +
                        "Changes apply instantly to all members with that role.");
            }
            case "perm-remove" -> {
                if (!isOwner(e)) { replyError(e, "Owner only."); return; }
                var roleOpt = e.getOption("role");
                if (roleOpt == null) { replyError(e, "Missing role."); return; }
                String removed = store.roles.remove(roleOpt.getAsRole().getId());
                saveStore();
                audit(e, "removed role mapping " + roleOpt.getAsRole().getId());
                replyInfo(e, removed != null ? "✅ Removed mapping for " + roleOpt.getAsRole().getAsMention()
                                             : "That role had no mapping.");
            }
            default -> replyError(e, "Unknown subcommand.");
        }
    }

    // ---- status builders ----

    private static final java.time.Instant BOOT = java.time.Instant.now();

    private String buildStatus(MinecraftServer server) {
        double[] tps = server.getTPS();
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);

        Color c = mspt < 40 ? new Color(0x2ECC71) : mspt < 50 ? new Color(0xF1C40F) : new Color(0xE74C3C);
        MessageEmbed em = new EmbedBuilder()
                .setTitle("🟢 Server Status")
                .setColor(c)
                .addField("Players", server.getPlayerCount() + "/" + server.getMaxPlayers(), true)
                .addField("TPS", String.format("%.1f / %.1f / %.1f", tps[0], tps[1], tps[2]), true)
                .addField("MSPT", String.format("%.1f ms", mspt), true)
                .addField("RAM", usedMb + " / " + maxMb + " MB", true)
                .addField("Uptime", formatUptime(System.currentTimeMillis() - BOOT.toEpochMilli()), true)
                .build();
        StringBuilder sb = new StringBuilder("**Server Status**\n");
        for (MessageEmbed.Field f : em.getFields()) {
            sb.append("**").append(f.getName()).append(":** ").append(f.getValue()).append("\n");
        }
        return sb.toString();
    }

    private String buildList(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return "Nobody is online.";
        StringBuilder sb = new StringBuilder("**Online (" + players.size() + "/"
                + server.getMaxPlayers() + ")**\n");
        for (int i = 0; i < players.size() && i < 100; i++) {
            sb.append("• ").append(players.get(i).getGameProfile().name()).append("\n");
        }
        return sb.toString();
    }

    private String buildPerf(MinecraftServer server) {
        double[] tps = server.getTPS();
        return String.format("**TPS:** %.1f / %.1f / %.1f\n**MSPT:** %.2f ms",
                tps[0], tps[1], tps[2], server.getAverageTickTimeNanos() / 1_000_000.0);
    }

    private static String formatUptime(long ms) {
        long s = ms / 1000, m = s / 60, h = m / 60;
        return String.format("%dh %dm", h, m % 60);
    }

    // ---- moderation ----

    private void handleKick(SlashCommandInteractionEvent e, MinecraftServer server) {
        require(e, Level.HELPER);
        String player = e.getOption("player", "", OptionMapping::getAsString);
        String reason = e.getOption("reason", "Kicked via Discord", OptionMapping::getAsString);
        var target = server.getPlayerList().getPlayerByName(player);
        if (target == null) { replyError(e, "'" + player + "' is not online."); return; }

        audit(e, "KICK " + target.getGameProfile().name() + " (" + reason + ")");
        server.execute(() -> target.connection.disconnect(
                net.minecraft.network.chat.Component.literal("Kicked: " + reason),
                org.bukkit.event.player.PlayerKickEvent.Cause.KICK_COMMAND));
        replyInfo(e, "👢 Kicked **" + target.getGameProfile().name() + "** — " + reason);
    }

    private record PendingAction(UUID id, String type, String player, String reason, long userId) {}
    private static final Map<String, PendingAction> PENDING_ACTIONS = new ConcurrentHashMap<>();

    private void handleBan(SlashCommandInteractionEvent e, MinecraftServer server) {
        require(e, Level.MOD);
        String player = e.getOption("player", "", OptionMapping::getAsString);
        String reason = e.getOption("reason", "Banned via Discord", OptionMapping::getAsString);

        UUID actionId = UUID.randomUUID();
        PENDING_ACTIONS.put(actionId.toString(), new PendingAction(actionId, "ban", player, reason, e.getUser().getIdLong()));

        e.replyEmbeds(new EmbedBuilder()
                        .setDescription("Ban **" + player + "**?\nReason: " + reason)
                        .setColor(new Color(0xE67E22))
                        .build())
                .addActionRow(
                        Button.danger("act:" + actionId, "Confirm ban"),
                        Button.secondary("act-cancel:" + actionId, "Cancel"))
                .setEphemeral(true)
                .queue();
    }

    private void executeBan(SlashCommandInteractionEvent ctx, PendingAction pa) {
        audit(ctx, "BAN " + pa.player() + " (" + pa.reason() + ")");
        doBan(pa.player(), pa.reason());
        replyInfo(ctx, "🔨 Banned **" + pa.player() + "** — " + pa.reason());
    }

    private void handlePardon(SlashCommandInteractionEvent e, MinecraftServer server) {
        require(e, Level.MOD);
        String player = e.getOption("player", "", OptionMapping::getAsString);
        audit(e, "PARDON " + player);
        server.execute(() -> {
            var resolved = server.services().nameToIdCache().get(player);
            resolved.ifPresent(nameAndId -> server.getPlayerList().getBans().remove(nameAndId));
        });
        replyInfo(e, "✅ Pardoned **" + player + "** (if they were banned)");
    }

    private void handleWhitelist(SlashCommandInteractionEvent e, MinecraftServer server) {
        require(e, Level.MOD);
        var sub = e.getSubcommandName();
        switch (sub == null ? "" : sub) {
            case "add", "remove" -> {
                String player = e.getOption("player", "", OptionMapping::getAsString);
                audit(e, "WHITELIST " + sub.toUpperCase() + " " + player);
                server.execute(() -> server.getCommands().performPrefixedCommand(
                        server.createCommandSourceStack(),
                        "whitelist " + sub + " " + player));
                replyInfo(e, "✅ Whitelist " + sub + ": **" + player + "**");
            }
            case "list" -> {
                StringBuilder sb = new StringBuilder("**Whitelist**\n");
                for (var entry2 : server.getPlayerList().getWhiteList().getEntries()) {
                    var user = entry2.getUser();
                    sb.append("• ").append(user != null && user.name() != null ? user.name() : String.valueOf(user == null ? "?" : user.id())).append("\n");
                }
                replyInfo(e, sb.length() > 20 ? sb.toString() : "Whitelist is empty.");
            }
            default -> replyError(e, "Unknown subcommand.");
        }
    }

    private void handleSay(SlashCommandInteractionEvent e, MinecraftServer server) {
        require(e, Level.ADMIN);
        String msg = e.getOption("message", "", OptionMapping::getAsString);
        audit(e, "SAY " + msg);
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                net.minecraft.network.chat.Component.literal("[Server] " + msg), false));
        replyInfo(e, "📢 Broadcast sent.");
    }

    private void handleConsole(SlashCommandInteractionEvent e, MinecraftServer server) {
        require(e, Level.ADMIN);
        String cmdRaw = e.getOption("command", "", OptionMapping::getAsString).trim();
        final String cmd = cmdRaw.startsWith("/") ? cmdRaw.substring(1) : cmdRaw;
        audit(e, "CONSOLE `" + cmd + "`");
        server.execute(() -> {
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
            } catch (Exception ex) {
                LOGGER.warn("Console command failed: {}", ex.getMessage());
            }
        });
        replyInfo(e, "🖥️ Executed: `" + cmd + "`");
    }

    // ------------------------------------------------------------------
    // Buttons (confirmations)
    // ------------------------------------------------------------------

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        String id = e.getComponentId();
        String[] parts = id.split(":", 2);
        if (!parts[0].equals("act")) { e.reply("Expired.").setEphemeral(true).queue(); return; }
        PendingAction pa = PENDING_ACTIONS.remove(parts[1]);
        if (pa == null) { e.editMessage("⌛ This confirmation expired.").setComponents().queue(); return; }
        if (pa.userId() != e.getUser().getIdLong()) {
            e.reply("Only whoever ran the command can confirm.").setEphemeral(true).queue();
            return;
        }

        // Re-check permission at confirm time (roles may have changed)
        boolean allowed = isOwner(e);
        if (!allowed && e.getMember() != null) {
            Level best = Level.NONE;
            for (var role : e.getMember().getRoles()) {
                String lvlName = store.roles.get(role.getId());
                if (lvlName == null) continue;
                Level l = Level.of(lvlName);
                if (l.rank > best.rank) best = l;
            }
            allowed = best.rank >= Level.MOD.rank;
        }

        if (!allowed) {
            e.editMessage("❌ Your roles no longer permit this action.").setComponents().queue();
            return;
        }

        switch (pa.type()) {
            case "ban" -> executeBanFromButton(e, pa);
            default -> e.editMessage("Unknown action.").setComponents().queue();
        }
    }

    private void executeBanFromButton(ButtonInteractionEvent e, PendingAction pa) {
        LOGGER.info("[Discord] user {} -> BAN(confirm) {} ({})", e.getUser().getId(), pa.player(), pa.reason());
        doBan(pa.player(), pa.reason());
        e.editMessage("🔨 Banned **" + pa.player() + "**").setComponents().queue();
    }

    /** Runs on the main thread: resolves name->NameAndId and applies the ban. */
    private void doBan(String playerName, String reason) {
        MinecraftServer server = MinecraftServer.getServer();
        server.execute(() -> {
            try {
                var resolved = server.services().nameToIdCache().get(playerName);
                net.minecraft.server.players.NameAndId nameAndId = resolved.orElse(null);
                if (nameAndId == null) {
                    // never seen before: ban by exact name via offline profile
                    nameAndId = new net.minecraft.server.players.NameAndId(
                            UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)),
                            playerName);
                }
                var entry = new net.minecraft.server.players.UserBanListEntry(nameAndId, null, "Discord", null, reason);
                server.getPlayerList().getBans().add(entry);
                var online = server.getPlayerList().getPlayer(nameAndId.id());
                if (online != null) {
                    online.connection.disconnect(net.minecraft.network.chat.Component.literal("Banned: " + reason),
                            org.bukkit.event.player.PlayerKickEvent.Cause.BANNED);
                }
            } catch (Exception ex) {
                LOGGER.error("Discord ban failed for {}", playerName, ex);
            }
        });
    }

    private static void auditSynthetic(String userId, String action) {
        LOGGER.info("[Discord] user {} -> {}", userId, action);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

}
