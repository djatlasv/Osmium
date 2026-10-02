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
import org.osmium.OsmiumReport;
import org.osmium.OsmiumStaffLog;

import java.awt.Color;
import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.EnumSet;
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
        public final int rank;
        Level(int r) { rank = r; }
        public static Level of(String s) {
            try { return valueOf(s.toUpperCase(Locale.ROOT)); }
            catch (Exception e) { return NONE; }
        }
    }

    private static class StoreData {
        String ownerId = "";      // discord user id of setup owner
        String guildId = "";      // the ONLY guild this bot operates in
        String logChannelId = ""; // staff log channel (auto-created on setup, /osmium log-channel)
        Map<String, String> roles = new LinkedHashMap<>(); // roleId -> LEVEL name
        Map<String, Link> links = new LinkedHashMap<>();   // minecraft uuid -> link
    }

    /** Minecraft <-> Discord staff link (persisted). Created via /link + /osmium link. */
    private static class Link {
        String discordId;
        String discordName;
        String mcName;
        long linkedAt;
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
    // Staff log channel + Minecraft<->Discord linking
    // ------------------------------------------------------------------

    private static volatile TextChannel staffLogCh;

    private static TextChannel staffLogChannel() {
        TextChannel ch = staffLogCh;
        if (ch != null) return ch;
        String id = store.logChannelId;
        if (id == null || id.isBlank()) return null;
        ch = jda != null ? jda.getTextChannelById(id.trim()) : null;
        if (ch != null) staffLogCh = ch;
        return ch;
    }

    private static final EnumSet<net.dv8tion.jda.api.Permission> STAFF_LOG_PERMS =
            EnumSet.of(net.dv8tion.jda.api.Permission.VIEW_CHANNEL,
                    net.dv8tion.jda.api.Permission.MESSAGE_SEND,
                    net.dv8tion.jda.api.Permission.MESSAGE_EMBED_LINKS,
                    net.dv8tion.jda.api.Permission.MESSAGE_HISTORY);

    /**
     * Sends a staff action line to the staff log channel. No-op when the bot
     * is off or no channel is configured — logging must never break gameplay.
     */
    public static void pushStaffLog(String text) {
        TextChannel ch = staffLogChannel();
        if (ch == null) return;
        ch.sendMessage(sanitize(text)).queue(ok -> {},
                err -> LOGGER.warn("Staff log send failed: {}", err.getMessage()));
    }

    public static boolean isLinked(UUID mcUuid) {
        return store.links.containsKey(mcUuid.toString());
    }

    /** Discord display name linked to this Minecraft account, or null. */
    public static String linkedDiscordName(UUID mcUuid) {
        Link l = store.links.get(mcUuid.toString());
        return l != null ? l.discordName : null;
    }

    /** Minecraft name linked to this Discord user id, or null. */
    public static String linkedMcName(String discordUserId) {
        for (Link l : store.links.values()) {
            if (l.discordId != null && l.discordId.equals(discordUserId)) return l.mcName;
        }
        return null;
    }

    /** Links a Minecraft account to a Discord user; drops any previous link for either side. */
    public static void setLink(UUID mcUuid, String mcName, String discordId, String discordName) {
        store.links.values().removeIf(l -> discordId.equals(l.discordId));
        Link l = new Link();
        l.discordId = discordId;
        l.discordName = discordName;
        l.mcName = mcName;
        l.linkedAt = System.currentTimeMillis();
        store.links.put(mcUuid.toString(), l);
        saveStore();
    }

    public static boolean removeLink(UUID mcUuid) {
        boolean removed = store.links.remove(mcUuid.toString()) != null;
        if (removed) saveStore();
        return removed;
    }

    private static boolean removeLinkByDiscord(String discordId) {
        boolean removed = store.links.values().removeIf(l -> discordId.equals(l.discordId));
        if (removed) saveStore();
        return removed;
    }

    /**
     * Creates a private staff-log channel (or keeps the existing one if it
     * still resolves). @everyone is denied view; the guild owner, the setup
     * owner and every role mapped to helper+ get access.
     */
    private void ensureStaffLogChannel(net.dv8tion.jda.api.entities.Guild guild) {
        JDA api = jda;
        if (api == null || guild == null) return;
        String current = store.logChannelId;
        if (current != null && !current.isBlank() && api.getTextChannelById(current.trim()) != null) return;

        var action = guild.createTextChannel("osmium-staff-log")
                .setTopic("Osmium staff action log (auto-created)")
                .addPermissionOverride(guild.getPublicRole(), null,
                        EnumSet.of(net.dv8tion.jda.api.Permission.VIEW_CHANNEL,
                                net.dv8tion.jda.api.Permission.MESSAGE_SEND,
                                net.dv8tion.jda.api.Permission.MESSAGE_HISTORY));
        var owner = guild.getOwner();
        if (owner != null) action = action.addPermissionOverride(owner, STAFF_LOG_PERMS, null);
        for (Map.Entry<String, String> entry : store.roles.entrySet()) {
            if (Level.of(entry.getValue()).rank >= Level.HELPER.rank) {
                var role = guild.getRoleById(entry.getKey());
                if (role != null) action = action.addPermissionOverride(role, STAFF_LOG_PERMS, null);
            }
        }
        action.queue(ch -> {
            store.logChannelId = ch.getId();
            saveStore();
            staffLogCh = ch;
            LOGGER.info("[Discord] staff log channel created: {}", ch.getId());
            pushStaffLog("\ud83d\udccb Staff log channel created — in-game staff commands and Discord "
                    + "moderation actions will be posted here. Staff: run /link in game, then "
                    + "/osmium link code:<code> here to attach your identity.");
        }, err -> LOGGER.error("Failed to create staff log channel: {}", err.getMessage()));
    }

    private void grantLogAccess(net.dv8tion.jda.api.entities.Guild guild, String roleId) {
        TextChannel ch = staffLogChannel();
        if (ch == null) return;
        var role = guild.getRoleById(roleId);
        if (role == null) return;
        ch.upsertPermissionOverride(role).setAllowed(STAFF_LOG_PERMS)
                .queue(ok -> {}, err -> LOGGER.warn("Failed to grant log access to role {}: {}", roleId, err.getMessage()));
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
            boolean bridge = OsmiumConfig.discordBotChatChannelId != null
                    && !OsmiumConfig.discordBotChatChannelId.isBlank();
            jda = JDABuilder.createDefault(token)
                    .enableIntents(bridge ? EnumSet.of(GatewayIntent.MESSAGE_CONTENT) : EnumSet.noneOf(GatewayIntent.class))
                    .disableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_PRESENCES,
                            GatewayIntent.GUILD_MESSAGE_TYPING)
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
        // Existing installs: create the staff log channel if the bound guild has none yet
        if (!store.guildId.isEmpty() && (store.logChannelId == null || store.logChannelId.isBlank())) {
            var guild = event.getJDA().getGuildById(store.guildId);
            if (guild != null) ensureStaffLogChannel(guild);
        }
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
                                        .addOption(OptionType.ROLE, "role", "Discord role", true),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("log-channel", "Set or create the staff log channel (owner)")
                                        .addOption(OptionType.CHANNEL, "channel", "Existing channel to use (leave empty to create a new private one)", false),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("links", "List Minecraft <-> Discord links (owner)"),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("link", "Link your Minecraft account (staff) — run /link in game first")
                                        .addOption(OptionType.STRING, "code", "Code from /link", true),
                                new net.dv8tion.jda.api.interactions.commands.build.SubcommandData("unlink", "Remove your Minecraft link")),

                Commands.slash("status", "Server status (viewer)"),
                Commands.slash("list", "Online players (viewer)"),
                Commands.slash("tps", "Server performance (viewer)"),

                Commands.slash("kick", "Kick a player (helper+)")
                        .addOption(OptionType.STRING, "player", "Player name", true)
                        .addOption(OptionType.STRING, "reason", "Reason", false),

                Commands.slash("ban", "Ban a player (mod+) — asks for confirmation")
                        .addOption(OptionType.STRING, "player", "Player name", true)
                        .addOption(OptionType.STRING, "reason", "Reason", false),

                Commands.slash("tempban", "Ban a player temporarily (mod+) — e.g. 2h, 90m, 1d12h")
                        .addOption(OptionType.STRING, "player", "Player name", true)
                        .addOption(OptionType.STRING, "duration", "Duration, e.g. 2h / 90m / 1d12h30m (units: s m h d w)", true)
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

    /**
     * True only inside the BOUND guild (set at first setup). Any other guild
     * where the bot may have been invited is ignored entirely — this prevents
     * a foreign guild owner from hijacking global ownership.
     */
    private static boolean inBoundGuild(net.dv8tion.jda.api.entities.Guild guild) {
        if (guild == null) return false;
        return store.guildId.isEmpty() || guild.getId().equals(store.guildId);
    }

    private boolean isOwner(SlashCommandInteractionEvent e) {
        if (!inBoundGuild(e.getGuild())) return false;
        if (e.getUser().getIdLong() == e.getGuild().getOwnerIdLong()) return true; // real guild owner of the bound guild
        return !store.ownerId.isEmpty() && e.getUser().getId().equals(store.ownerId);
    }

    private boolean isOwner(ButtonInteractionEvent e) {
        if (!inBoundGuild(e.getGuild())) return false;
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
        String mc = linkedMcName(e.getUser().getId());
        String line = "[Discord] " + e.getUser().getName()
                + (mc != null ? " [" + mc + "]" : "")
                + " (" + e.getUser().getId() + ") -> " + action;
        LOGGER.info(line);
        auditChannel(e.getGuild(), line);
    }

    private void auditChannel(net.dv8tion.jda.api.entities.Guild guild, String text) {
        // Prefer the staff log channel; fall back to the legacy audit-channel-id.
        if (staffLogChannel() != null) { pushStaffLog("`" + text + "`"); return; }
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
        } catch (LinkRequiredException ex) {
            replyError(e, "You must link your Minecraft account first: run **/link** in game, "
                    + "then `/osmium link code:<code>` here.");
        } catch (Exception ex) {
            LOGGER.error("Discord command error", ex);
            replyError(e, "Internal error: " + ex.getMessage());
        }
    }

    private void handleSlash(SlashCommandInteractionEvent e) {
        if (e.getGuild() == null) { replyError(e, "Server commands only work inside a guild."); return; }
        if (!inBoundGuild(e.getGuild())) {
            // Bot was invited somewhere else: allow setup ONLY if unbound, otherwise stay silent-ish.
            if ("osmium".equals(e.getName()) && "setup".equals(e.getSubcommandName()) && store.guildId.isEmpty()) {
                // fall through to setup below — it will bind to this guild
            } else {
                replyError(e, "This bot is bound to another server.");
                return;
            }
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) { replyError(e, "Server is not running."); return; }

        switch (e.getName()) {
            case "osmium" -> handleOsmiumAdmin(e, server);
            case "status" -> { require(e, Level.VIEWER); replyInfo(e, buildStatus(server)); }
            case "list" -> { require(e, Level.VIEWER); replyInfo(e, buildList(server)); }
            case "tps" -> { require(e, Level.VIEWER); replyInfo(e, buildPerf(server)); }
            case "kick" -> handleKick(e, server);
            case "ban" -> handleBan(e, server);
            case "tempban" -> handleTempban(e, server);
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
        // Staff actions require a linked Minecraft account — keeps the staff
        // log attributed and proves the operator is a real in-game identity.
        if (required.rank >= Level.HELPER.rank && linkedMcName(e.getUser().getId()) == null) {
            throw new LinkRequiredException();
        }
    }

    private static final class PermissionDeniedException extends RuntimeException {
        PermissionDeniedException(Level required) {
            super("requires " + required);
        }
    }

    private static final class LinkRequiredException extends RuntimeException {}

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
            case "link" -> handleLink(e);
            case "unlink" -> handleUnlink(e);
            case "setup" -> {
                if (!store.ownerId.isEmpty() && !isOwner(e)) {
                    replyError(e, "Bot already owned by <@" + store.ownerId + ">. The Discord **guild owner** can reclaim with this command.");
                    return;
                }
                store.ownerId = e.getUser().getId();
                store.guildId = e.getGuild().getId();
                // A fresh bind starts with clean role mappings
                if (!store.roles.isEmpty()) store.roles.clear();
                saveStore();
                LOGGER.info("[Discord] Bound to guild {} by {}", store.guildId, e.getUser().getId());
                audit(e, "became bot owner");
                ensureStaffLogChannel(e.getGuild());
                replyInfo(e, "✅ You are now the bot owner.\n\nNext steps:\n" +
                        "1. Create Discord roles for your staff (or reuse existing ones)\n" +
                        "2. `/osmium perm-add role:<role> level:<viewer|helper|mod|admin>`\n" +
                        "3. Members with mapped roles gain matching abilities.\n" +
                        "4. A private **osmium-staff-log** channel was created — staff run `/link` in game, "
                        + "then `/osmium link code:<code>` here to attach their identity to the action log.");
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
                if (lvl.rank >= Level.HELPER.rank) grantLogAccess(e.getGuild(), roleId);
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
            case "log-channel" -> {
                if (!isOwner(e)) { replyError(e, "Owner only."); return; }
                var chOpt = e.getOption("channel");
                if (chOpt != null && chOpt.getAsChannel() instanceof TextChannel tc) {
                    store.logChannelId = tc.getId();
                    saveStore();
                    staffLogCh = null;
                    audit(e, "set staff log channel to " + tc.getId());
                    pushStaffLog("\ud83d\udccb Staff log channel set to this channel.");
                    replyInfo(e, "✅ Staff log channel set to " + tc.getAsMention());
                } else {
                    String current = store.logChannelId;
                    if (current != null && !current.isBlank()
                            && e.getJDA().getTextChannelById(current.trim()) != null) {
                        replyInfo(e, "Staff log channel already exists: <#" + current.trim() + ">. "
                                + "Pass a channel to replace it, or delete that channel first to recreate.");
                        return;
                    }
                    ensureStaffLogChannel(e.getGuild());
                    replyInfo(e, "✅ Creating a new private **osmium-staff-log** channel...");
                }
            }
            case "links" -> {
                if (!isOwner(e)) { replyError(e, "Owner only."); return; }
                if (store.links.isEmpty()) { replyInfo(e, "No linked accounts. Staff run `/link` in game, then `/osmium link code:<code>`."); return; }
                StringBuilder sb = new StringBuilder("**Minecraft <-> Discord links**\n");
                for (var link : store.links.values()) {
                    sb.append("• ").append(link.mcName).append(" ↔ **").append(link.discordName)
                      .append("** (<@").append(link.discordId).append(">)\n");
                }
                replyInfo(e, sb.toString());
            }
            default -> replyError(e, "Unknown subcommand.");
        }
    }

    // ---- staff linking ----

    private void handleLink(SlashCommandInteractionEvent e) {
        if (!has(e, Level.HELPER)) throw new PermissionDeniedException(Level.HELPER); // role check only — /link is how you GET linked
        String code = e.getOption("code", "", OptionMapping::getAsString);
        OsmiumStaffLog.PendingLink p = OsmiumStaffLog.claim(code);
        if (p == null) {
            replyError(e, "Invalid or expired code. Run **/link** in game to get a fresh one.");
            return;
        }
        setLink(p.uuid(), p.name(), e.getUser().getId(), e.getUser().getName());
        audit(e, "linked Discord account to Minecraft **" + p.name() + "**");
        pushStaffLog("\ud83d\udd17 " + e.getUser().getName() + " linked their Discord account to Minecraft **" + p.name() + "**");
        replyInfo(e, "✅ Linked to Minecraft account **" + p.name() + "**.\n"
                + "Your in-game staff actions will now carry your Discord identity in the staff log.");
    }

    private void handleUnlink(SlashCommandInteractionEvent e) {
        require(e, Level.VIEWER);
        String mc = linkedMcName(e.getUser().getId());
        if (mc == null) { replyError(e, "Your Discord account is not linked."); return; }
        removeLinkByDiscord(e.getUser().getId());
        audit(e, "unlinked Discord account from Minecraft " + mc);
        pushStaffLog("\u274c " + e.getUser().getName() + " unlinked their Discord account (was " + mc + ")");
        replyInfo(e, "✅ Link removed (was **" + mc + "**).");
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

    private record PendingAction(UUID id, String type, String player, String reason, long userId, long createdAt,
                                 long durationMs) {
        boolean expired() { return System.currentTimeMillis() - createdAt > 10 * 60_000L; }
    }
    private static final Map<String, PendingAction> PENDING_ACTIONS = new ConcurrentHashMap<>();

    /**
     * Parses combined durations like "2h", "90m", "1d12h30m". Returns the
     * duration in ms, or -1 if invalid/empty. Allowed units: s/m/h/d/w.
     * Clamped to [1 minute, 365 days].
     */
    private static long parseDuration(String s) {
        if (s == null || s.isBlank()) return -1;
        var m = java.util.regex.Pattern.compile("(\\d+)([smhdw])").matcher(s.toLowerCase(Locale.ROOT));
        long total = 0;
        int matches = 0;
        int lastEnd = 0;
        while (m.find()) {
            if (m.start() != lastEnd) return -1; // stray characters between units
            long v = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "s" -> v;
                case "m" -> v * 60;
                case "h" -> v * 3600;
                case "d" -> v * 86400;
                default -> v * 604800; // w
            } * 1000L;
            matches++;
            lastEnd = m.end();
        }
        if (matches == 0 || lastEnd != s.length()) return -1;
        if (total < 60_000L) return -1;
        return Math.min(total, 365L * 86400_000L);
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600;  s %= 3600;
        long m = s / 60;    s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.isEmpty()) sb.append(s).append("s");
        return sb.toString().trim();
    }

    private void handleBan(SlashCommandInteractionEvent e, MinecraftServer server) {
        require(e, Level.MOD);
        String player = e.getOption("player", "", OptionMapping::getAsString);
        String reason = e.getOption("reason", "Banned via Discord", OptionMapping::getAsString);

        askBanConfirm(e, "ban", player, reason, 0,
                "Ban **" + player + "**?\nReason: " + reason, "Confirm ban");
    }

    private void handleTempban(SlashCommandInteractionEvent e, MinecraftServer server) {
        require(e, Level.MOD);
        String player = e.getOption("player", "", OptionMapping::getAsString);
        long duration = parseDuration(e.getOption("duration", "", OptionMapping::getAsString));
        if (duration < 0) {
            replyError(e, "Invalid duration. Use formats like **2h**, **90m**, **1d12h30m** (units: s/m/h/d/w, max 365d).");
            return;
        }
        String reason = e.getOption("reason", "Temp-banned via Discord", OptionMapping::getAsString);
        String durText = formatDuration(duration);

        askBanConfirm(e, "tempban", player, reason, duration,
                "Temp-ban **" + player + "** for **" + durText + "**?\nReason: " + reason,
                "Confirm temp-ban");
    }

    private void askBanConfirm(SlashCommandInteractionEvent e, String type, String player, String reason,
                               long durationMs, String prompt, String confirmLabel) {
        UUID actionId = UUID.randomUUID();
        PENDING_ACTIONS.put(actionId.toString(), new PendingAction(actionId, type, player, reason,
                e.getUser().getIdLong(), System.currentTimeMillis(), durationMs));
        // Opportunistic cleanup: drop stale confirmations
        if (PENDING_ACTIONS.size() > 32) {
            PENDING_ACTIONS.values().removeIf(PendingAction::expired);
        }

        e.replyEmbeds(new EmbedBuilder()
                        .setDescription(prompt)
                        .setColor(new Color(0xE67E22))
                        .build())
                .addActionRow(
                        Button.danger("act:" + actionId, confirmLabel),
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
                var base = server.createCommandSourceStack();
                var capture = new CapturingCommandSource(base.source);
                server.getCommands().performPrefixedCommand(base.withSource(capture), cmd);
                replyInfo(e, "Executed: `" + cmd + "`\n" + capture.captured());
            } catch (Exception ex) {
                LOGGER.warn("Console command failed: {}", ex.getMessage());
                replyError(e, "Command failed: " + ex.getMessage());
            }
        });
    }


    // ------------------------------------------------------------------
    // Buttons (confirmations)
    // ------------------------------------------------------------------

    @Override
    public void onButtonInteraction(ButtonInteractionEvent e) {
        if (!inBoundGuild(e.getGuild())) { e.reply("This bot is bound to another server.").setEphemeral(true).queue(); return; }
        String id = e.getComponentId();
        if (id.startsWith("report:")) { handleReportButton(e); return; }
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
        if (linkedMcName(e.getUser().getId()) == null) {
            e.editMessage("❌ Link required — run **/link** in game, then `/osmium link code:<code>` here.").setComponents().queue();
            return;
        }

        switch (pa.type()) {
            case "ban" -> executeBanFromButton(e, pa);
            case "tempban" -> executeTempbanFromButton(e, pa);
            default -> e.editMessage("Unknown action.").setComponents().queue();
        }
    }

    private void executeBanFromButton(ButtonInteractionEvent e, PendingAction pa) {
        LOGGER.info("[Discord] user {} -> BAN(confirm) {} ({})", e.getUser().getId(), pa.player(), pa.reason());
        doBan(pa.player(), pa.reason());
        e.editMessage("🔨 Banned **" + pa.player() + "**").setComponents().queue();
    }

    private void executeTempbanFromButton(ButtonInteractionEvent e, PendingAction pa) {
        String durText = formatDuration(pa.durationMs());
        LOGGER.info("[Discord] user {} -> TEMPBAN(confirm) {} ({} — {})", e.getUser().getId(), pa.player(), durText, pa.reason());
        Date expires = new Date(System.currentTimeMillis() + pa.durationMs());
        OsmiumReport.banPlayer(pa.player(), null, pa.reason(), expires, "Discord temp-ban by " + e.getUser().getName());
        String mc = linkedMcName(e.getUser().getId());
        auditChannel(e.getGuild(), "[Discord] " + e.getUser().getName()
                + (mc != null ? " [" + mc + "]" : "")
                + " (" + e.getUser().getId() + ") -> tempban " + pa.player() + " (" + durText + ")");
        pushStaffLog("\u26d4 " + e.getUser().getName() + " temp-banned **" + pa.player()
                + "** for " + durText + " \u2014 " + pa.reason());
        e.editMessage("\u23f3 Temp-banned **" + pa.player() + "** for " + durText).setComponents().queue();
    }

    /** Runs on the main thread: resolves name->NameAndId and applies the ban. */
    private void doBan(String playerName, String reason) {
        OsmiumReport.banPlayer(playerName, null, reason, null, "Discord");
    }

    // ------------------------------------------------------------------
    // Player reports (/report -> embed + action buttons)
    // ------------------------------------------------------------------

    private static TextChannel reportChannel(JDA api) {
        String id = OsmiumConfig.reportChannelId;
        if (id == null || id.isBlank()) id = store.logChannelId;
        if (id == null || id.isBlank()) id = OsmiumConfig.discordBotAuditChannelId;
        if (id == null || id.isBlank()) return null;
        return api.getTextChannelById(id.trim());
    }

    /** Called from the /report command (main thread). Queues the embed + action buttons. */
    public static boolean pushReport(OsmiumReport.Report r) {
        JDA api = jda;
        if (api == null) {
            LOGGER.warn("Report {} not delivered: Discord bot is not running", r.id());
            return false;
        }
        TextChannel ch = reportChannel(api);
        if (ch == null) {
            LOGGER.warn("Report {} not delivered: no report channel configured — set report.channel-id "
                    + "or discord-bot.audit-channel-id in osmium.yml", r.id());
            return false;
        }
        var self = ch.getGuild().getSelfMember();
        if (!self.hasPermission(ch, net.dv8tion.jda.api.Permission.MESSAGE_SEND)
                || !self.hasPermission(ch, net.dv8tion.jda.api.Permission.MESSAGE_EMBED_LINKS)) {
            LOGGER.error("Report {} not delivered: bot lacks Send Messages / Embed Links permission in #{} ({})",
                    r.id(), ch.getName(), ch.getId());
            return false;
        }
        ch.sendMessageEmbeds(reportEmbed(r, null))
                .addActionRow(
                        Button.secondary("report:dismiss:" + r.id(), "Dismiss"),
                        Button.danger("report:timeban:" + r.id(), "Time ban (" + OsmiumConfig.reportTimeBanHours + "h)"),
                        Button.danger("report:permban:" + r.id(), "Ban"))
                .queue(ok -> {},
                        err -> LOGGER.error("Failed to push report {}: {}", r.id(), err.getMessage()));
        return true;
    }

    private static MessageEmbed reportEmbed(OsmiumReport.Report r, String statusLine) {
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("New player report")
                .setColor(new Color(0xE67E22))
                .addField("Reporter", r.reporterName() + " (`" + r.reporterUuid() + "`)", false)
                .addField("Reported", r.targetName() + " (`" + r.targetUuid() + "`)", false)
                .addField("Reason", r.reason(), false)
                .setFooter("Report " + r.id().toString().substring(0, 8)
                        + " — <t:" + (r.createdAt() / 1000) + ":R>");
        if (statusLine != null) eb.setDescription(statusLine);
        return eb.build();
    }

    private void handleReportButton(ButtonInteractionEvent e) {
        String[] parts = e.getComponentId().split(":", 3); // report:<action>:<uuid>
        UUID reportId = null;
        if (parts.length == 3) {
            try { reportId = UUID.fromString(parts[2]); } catch (IllegalArgumentException ignored) {}
        }
        if (reportId == null) { e.reply("Malformed report id.").setEphemeral(true).queue(); return; }

        // Re-check permission at click time (roles may have changed) — mod tier
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
        if (!allowed) { e.reply("❌ You need the mod role to handle reports.").setEphemeral(true).queue(); return; }
        if (linkedMcName(e.getUser().getId()) == null) {
            e.reply("❌ Link required — run **/link** in game, then `/osmium link code:<code>` here.").setEphemeral(true).queue();
            return;
        }

        OsmiumReport.Report r = OsmiumReport.get(reportId);
        if (r == null) { e.reply("⌛ This report no longer exists (expired or pruned).").setEphemeral(true).queue(); return; }
        if (!OsmiumReport.isOpen(r)) {
            e.reply("This report was already handled by **" + r.resolvedBy() + "**.").setEphemeral(true).queue();
            return;
        }

        String actor = e.getUser().getName();
        String statusLine;
        switch (parts[1]) {
            case "dismiss" -> {
                statusLine = "✅ Dismissed by **" + actor + "**";
                LOGGER.info("[Discord] user {} -> REPORT {} dismiss {}",
                        e.getUser().getId(), r.id(), r.targetName());
            }
            case "timeban" -> {
                int hours = Math.max(1, OsmiumConfig.reportTimeBanHours);
                OsmiumReport.banPlayer(r.targetName(), r.targetUuid(), r.reason(),
                        new Date(System.currentTimeMillis() + hours * 3600_000L),
                        "Discord report by " + actor);
                statusLine = "🔨 **" + r.targetName() + "** banned for " + hours + "h by **" + actor
                        + "** — " + r.reason();
                LOGGER.info("[Discord] user {} -> REPORT {} timeban {} ({}h)",
                        e.getUser().getId(), r.id(), r.targetName(), hours);
            }
            case "permban" -> {
                OsmiumReport.banPlayer(r.targetName(), r.targetUuid(), r.reason(), null,
                        "Discord report by " + actor);
                statusLine = "🔨 **" + r.targetName() + "** permanently banned by **" + actor
                        + "** — " + r.reason();
                LOGGER.info("[Discord] user {} -> REPORT {} permban {}",
                        e.getUser().getId(), r.id(), r.targetName());
            }
            default -> { e.reply("Unknown action.").setEphemeral(true).queue(); return; }
        }

        OsmiumReport.Report updated = OsmiumReport.resolve(reportId,
                parts[1].equals("dismiss") ? "dismissed" : "banned", actor);
        String mc = linkedMcName(e.getUser().getId());
        auditChannel(e.getGuild(), "[Discord] " + actor + (mc != null ? " [" + mc + "]" : "")
                + " (" + e.getUser().getId() + ") -> report " + parts[1] + " " + r.targetName());
        e.editMessageEmbeds(reportEmbed(updated, statusLine)).setComponents().queue();
    }

    private static void auditSynthetic(String userId, String action) {
        LOGGER.info("[Discord] user {} -> {}", userId, action);
    }

    // ------------------------------------------------------------------
    // Chat bridge
    // ------------------------------------------------------------------

    private static volatile TextChannel chatChannel;

    private static TextChannel chatChannel(JDA api) {
        if (chatChannel != null) return chatChannel;
        String id = OsmiumConfig.discordBotChatChannelId;
        if (id == null || id.isBlank()) return null;
        TextChannel ch = api.getTextChannelById(id.trim());
        if (ch != null) chatChannel = ch;
        return ch;
    }

    /** Minecraft -> Discord. Called from the chat pipeline (any thread). */
    public static void onMinecraftChat(String playerName, String message) {
        JDA j = jda;
        if (j == null) return;
        String id = OsmiumConfig.discordBotChatChannelId;
        if (id == null || id.isBlank()) return;
        TextChannel ch = chatChannel(j);
        if (ch == null) return;
        String clean = sanitize(message);
        if (clean.isEmpty()) return;
        ch.sendMessage("`" + sanitize(playerName) + "` " + clean).queue(ok -> {}, err -> {});
    }

    /** Player joined / left / died notices. */
    public static void onPlayerEvent(String text) {
        JDA j = jda;
        if (j == null) return;
        TextChannel ch = chatChannel(j);
        if (ch == null) return;
        ch.sendMessage(sanitize(text)).queue(ok -> {}, err -> {});
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String out = s.replace("@everyone", "@\u200beveryone")
                      .replace("@here", "@\u200bhere");
        // strip discord markdown headers that break embeds/looks
        while (out.startsWith("#")) out = out.substring(1);
        return out.length() > 350 ? out.substring(0, 350) + "…" : out;
    }

    @Override
    public void onMessageReceived(net.dv8tion.jda.api.events.message.MessageReceivedEvent event) {
        try {
            if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
            String id = OsmiumConfig.discordBotChatChannelId;
            if (id == null || event.getChannel().getIdLong() != parseLongSafe(id)) return;
            if (!inBoundGuild(event.getGuild())) return;

            String content = event.getMessage().getContentDisplay();
            if (content.isBlank()) return;

            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) return;
            String name = event.getMember() != null ? event.getMember().getEffectiveName()
                                                    : event.getAuthor().getName();
            server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal("\u00a77[Discord] \u00a7f" + name + "\u00a77: \u00a7f" + sanitize(content)),
                    false));
        } catch (Exception ex) {
            LOGGER.warn("Bridge relay failed", ex);
        }
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1; }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void auditChannelText(net.dv8tion.jda.api.entities.Guild guild, String text) {
        auditChannel(guild, text);
    }
}
