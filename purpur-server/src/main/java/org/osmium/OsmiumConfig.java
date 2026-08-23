package org.osmium;

import com.google.common.base.Throwables;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.logging.Level;

public class OsmiumConfig {

    private static final String HEADER = "Osmium Configuration\n"
            + "Osmium is a custom Purpur fork with native anticheat and security features.\n"
            + "GitHub: https://github.com/djatlasv/Osmium\n"
            + "\n"
            + "--- chunk-hiding ---\n"
            + "Replaces all blocks below a Y level with a fake block in the chunk packet.\n"
            + "Players within the proximity radius see real blocks. Blocks re-hide when they leave.\n"
            + "  enabled: master toggle\n"
            + "  y-threshold: blocks below this Y level are hidden (0 = hide everything below sea level)\n"
            + "  block: the block to replace hidden blocks with (e.g. deepslate, stone, netherrack)\n"
            + "  hide-entities: also hide entities (mobs, items, etc.) below the threshold from distant players\n"
            + "  hide-light: zero out light data below the threshold to defeat Light Finder hacks\n"
            + "  proximity-radius: how many blocks around the player to reveal real blocks\n"
            + "\n"
            + "--- brand-enforcement ---\n"
            + "Enforces client mod rules using the HandShaker protocol at the NMS level.\n"
            + "  enabled: master toggle\n"
            + "  mode: 'strict' requires all clients to have HandShaker, 'vanilla' allows vanilla clients\n"
            + "  kick-message: message shown when a player is kicked for missing HandShaker or required mods\n"
            + "  blacklist-kick-message: message shown when kicked for a blacklisted mod — {mods} = detected mods\n"
            + "  check-delay-ticks: ticks to wait for HandShaker payload before checking (100 = 5 seconds)\n"
            + "  required-mods: mod IDs that must be installed (e.g. [hand-shaker])\n"
            + "  blacklisted-mods: mod IDs that trigger a kick (e.g. [wurst, meteor-client])\n"
            + "\n"
            + "--- alt-ban ---\n"
            + "Tracks IP-to-UUID and fingerprint-to-UUID associations for alt detection.\n"
            + "IP-based detection always works. Fingerprint-based detection requires brand-enforcement\n"
            + "to be enabled (fingerprints are sent via the HandShaker protocol).\n"
            + "  enabled: master toggle\n"
            + "  kick-message: message shown when an alt is kicked\n"
            + "\n"
            + "--- chat-filter ---\n"
            + "Filters chat messages using a word list from osmium-words.json.\n"
            + "Supports exact matches (case-insensitive) and regex: prefixed patterns.\n"
            + "  enabled: master toggle\n"
            + "  action: what to do on match — block (cancel message), kick, or mute\n"
            + "  message: message shown to the player when their message is filtered\n"
            + "\n"
            + "--- scoreboard ---\n"
            + "Custom sidebar scoreboard with live-updating placeholders.\n"
            + "  enabled: master toggle\n"
            + "  title: scoreboard title (supports & color codes)\n"
            + "  lines: list of lines (supports & color codes and placeholders)\n"
            + "  update-ticks: how often to refresh (20 = 1 second)\n"
            + "  Placeholders: {player} {ping} {kills} {deaths} {kd} {money} {online} {max} {tps}\n"
            + "\n"
            + "--- grim ---\n"
            + "Runs GrimAC 2.3.74 as a native server module instead of a plugin.\n"
            + "GrimAC configs live in the ./grim/ directory.\n"
            + "  enabled: master toggle\n"
            + "\n"
            + "--- rtp ---\n"
            + "Random Teleport GUI — /rtp opens a dimension picker.\n"
            + "  enabled: master toggle\n"
            + "  delay-seconds: countdown before teleporting\n"
            + "  cost: economy charge per teleport (0 = free, requires Vault + economy plugin)\n"
            + "  max-distance: maximum distance from world center\n"
            + "  min-distance: minimum distance from world center\n"
            + "\n"
            + "--- discord-webhook ---\n"
            + "Sends Discord embed notifications for server events (start/stop, bans, alts, etc.).\n"
            + "  enabled: master toggle\n"
            + "  url: your Discord webhook URL\n"
            + "  ping-on-stop: list of Discord user IDs to ping when the server stops (e.g. ['123456789'])\n";

    public static File CONFIG_FILE;
    public static YamlConfiguration config;
    public static int version = 6;

    public static void init(File configFile) {
        CONFIG_FILE = configFile;
        config = new YamlConfiguration();

        try {
            config.load(CONFIG_FILE);
        } catch (IOException ignore) {
        } catch (InvalidConfigurationException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not load osmium.yml, please correct your syntax errors", ex);
            throw Throwables.propagate(ex);
        }

        config.options().header(HEADER);
        config.options().copyDefaults(true);

        // Run migrations if config is outdated
        int oldVersion = config.getInt("config-version", 0);
        if (oldVersion < version) {
            migrate(oldVersion);
            Bukkit.getLogger().info("[Osmium] Config migrated from v" + oldVersion + " to v" + version);
        }

        config.set("config-version", version);
        readConfig(OsmiumConfig.class, null);
    }

    /**
     * Runs incremental migrations from oldVersion to current version.
     * Each migration step handles one version bump, preserving user settings
     * while adding new keys and removing obsolete ones.
     */
    private static void migrate(int oldVersion) {
        if (oldVersion < 2) {
            // v1 -> v2: merged chunk-hiding + y-level-hiding into one section
            if (config.contains("y-level-hiding")) {
                // Carry over y-level-hiding values if user had them set
                if (config.getBoolean("y-level-hiding.enabled", false)) {
                    config.set("chunk-hiding.enabled", true);
                }
                if (config.contains("y-level-hiding.threshold")) {
                    config.set("chunk-hiding.y-threshold", config.getInt("y-level-hiding.threshold"));
                }
                config.set("y-level-hiding", null); // remove obsolete section
            }
            // Old chunk-hiding had no 'block' key — default will be added by readConfig
        }

        if (oldVersion < 3) {
            // v2 -> v3: added blacklist-kick-message
            if (!config.contains("brand-enforcement.blacklist-kick-message")) {
                config.set("brand-enforcement.blacklist-kick-message",
                        "You have been kicked for using a blacklisted mod: {mods}");
            }
        }

        if (oldVersion < 4) {
            // v3 -> v4: added ping-on-stop
            if (!config.contains("discord-webhook.ping-on-stop")) {
                config.set("discord-webhook.ping-on-stop", List.of());
            }
        }

        if (oldVersion < 5) {
            // v4 -> v5: added scoreboard + require-osmium-handshaker
        }

        if (oldVersion < 6) {
            // v5 -> v6: added hide-light
        }
    }

    static void readConfig(Class<?> clazz, Object instance) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers())
                    && method.getParameterCount() == 0
                    && method.getReturnType() == Void.TYPE) {
                try {
                    method.setAccessible(true);
                    method.invoke(instance);
                } catch (InvocationTargetException ex) {
                    throw Throwables.propagate(ex.getCause());
                } catch (Exception ex) {
                    Bukkit.getLogger().log(Level.SEVERE, "Error invoking " + method, ex);
                }
            }
        }

        try {
            config.save(CONFIG_FILE);
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not save osmium.yml", ex);
        }
    }

    private static void set(String path, Object val) {
        config.addDefault(path, val);
        config.set(path, config.get(path, val));
    }

    private static boolean getBoolean(String path, boolean def) {
        config.addDefault(path, def);
        return config.getBoolean(path, config.getBoolean(path));
    }

    private static int getInt(String path, int def) {
        config.addDefault(path, def);
        return config.getInt(path, config.getInt(path));
    }

    // -------------------------------------------------------------------------
    // Chunk hiding settings
    // -------------------------------------------------------------------------

    public static boolean chunkHidingEnabled = false;
    public static int chunkHidingYThreshold = 0;
    public static String chunkHidingBlock = "deepslate";
    public static int chunkHidingProximityRadius = 32;
    public static boolean chunkHidingHideEntities = true;
    public static boolean chunkHidingHideLight = true;

    private static void chunkHiding() {
        chunkHidingEnabled = getBoolean("chunk-hiding.enabled", false);
        chunkHidingYThreshold = getInt("chunk-hiding.y-threshold", 0);
        chunkHidingBlock = config.getString("chunk-hiding.block", "deepslate");
        config.addDefault("chunk-hiding.block", chunkHidingBlock);
        chunkHidingProximityRadius = getInt("chunk-hiding.proximity-radius", 32);
        chunkHidingHideEntities = getBoolean("chunk-hiding.hide-entities", true);
        chunkHidingHideLight = getBoolean("chunk-hiding.hide-light", true);
    }

    private static void raytraceHiding() {
        raytraceHidingEnabled = getBoolean("raytrace-hiding.enabled", false);
        raytraceTargetBlocks = config.getStringList("raytrace-hiding.blocks");
        config.addDefault("raytrace-hiding.blocks", List.of(
                "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore",
                "gold_ore", "deepslate_gold_ore", "iron_ore", "deepslate_iron_ore",
                "ancient_debris"));
        raytraceMaxRayDistance = getInt("raytrace-hiding.max-ray-distance", 48);
        raytraceChecksPerTick = getInt("raytrace-hiding.checks-per-tick", 4);
        raytraceSamplesPerBlock = getInt("raytrace-hiding.samples-per-block", 2);
        raytraceRefreshSeconds = getInt("raytrace-hiding.refresh-seconds", 120);
        occlusionWorkerThreads = Math.max(1, getInt("raytrace-hiding.worker-threads", 2));
    }

    private static void tpa() {
        tpaEnabled = getBoolean("tpa.enabled", false);
        tpaTimeoutSeconds = Math.max(1, getInt("tpa.timeout-seconds", 60));
        tpaDelaySeconds = Math.max(0, getInt("tpa.delay-seconds", 3));
        tpaCooldownSeconds = Math.max(0, getInt("tpa.cooldown-seconds", 0));
        tpaDebug = getBoolean("tpa.debug", false);
    }

    private static void identityEnforcement() {
        signedChatKick = getBoolean("identity-enforcement.signed-chat-kick", false);
        signedChatKickMessage = config.getString("identity-enforcement.signed-chat-kick-message",
                "Unsigned chat detected. Modified clients that block chat signing are not allowed.");
        rejectModdedKnownPacks = getBoolean("identity-enforcement.reject-modded-known-packs", false);
        knownPacksKickMessage = config.getString("identity-enforcement.known-packs-kick-message",
                "Modified clients are not allowed on this server.");
    }

    private static void entityOcclusion() {
        entityOcclusionEnabled = getBoolean("entity-occlusion.enabled", false);
        entityOcclusionMaxDistance = getInt("entity-occlusion.max-distance", 48);
        entityOcclusionCheckIntervalTicks = getInt("entity-occlusion.check-interval-ticks", 10);
    }

    // -------------------------------------------------------------------------
    // Brand enforcement settings
    // -------------------------------------------------------------------------

    public static boolean brandEnforcementEnabled = false;
    public static String brandEnforcementMode = "vanilla";
    public static String brandEnforcementKickMessage = "You must use the HandShaker mod. Get it at: discord.gg/yourserver";
    public static String brandEnforcementBlacklistKickMessage = "You have been kicked for using a blacklisted mod: {mods}";
    public static boolean brandEnforcementRequireOsmiumHandshaker = false;
    public static int brandEnforcementCheckDelayTicks = 100;
    public static List<String> brandEnforcementRequiredMods = List.of();
    public static List<String> brandEnforcementBlacklistedMods = List.of();

    private static void brandEnforcement() {
        brandEnforcementEnabled = getBoolean("brand-enforcement.enabled", false);
        brandEnforcementMode = config.getString("brand-enforcement.mode", "vanilla");
        config.addDefault("brand-enforcement.mode", brandEnforcementMode);
        brandEnforcementKickMessage = config.getString("brand-enforcement.kick-message",
                "You must use the HandShaker mod. Get it at: discord.gg/yourserver");
        config.addDefault("brand-enforcement.kick-message", brandEnforcementKickMessage);
        brandEnforcementBlacklistKickMessage = config.getString("brand-enforcement.blacklist-kick-message",
                "You have been kicked for using a blacklisted mod: {mods}");
        config.addDefault("brand-enforcement.blacklist-kick-message", brandEnforcementBlacklistKickMessage);
        brandEnforcementRequireOsmiumHandshaker = getBoolean("brand-enforcement.require-osmium-handshaker", false);
        brandEnforcementCheckDelayTicks = getInt("brand-enforcement.check-delay-ticks", 100);
        brandEnforcementRequiredMods = config.getStringList("brand-enforcement.required-mods");
        config.addDefault("brand-enforcement.required-mods", List.of());
        brandEnforcementBlacklistedMods = config.getStringList("brand-enforcement.blacklisted-mods");
        config.addDefault("brand-enforcement.blacklisted-mods", List.of(
                "krloader", "krypton", "wurst", "meteor-client", "aristois", "meteor_client"));
    }

    // -------------------------------------------------------------------------
    // Alt ban / IP tracking settings
    // -------------------------------------------------------------------------

    public static boolean altBanEnabled = false;
    public static String altBanKickMessage = "You are banned (alt account detected).";

    private static void altBan() {
        altBanEnabled = getBoolean("alt-ban.enabled", false);
        altBanKickMessage = config.getString("alt-ban.kick-message",
                "You are banned (alt account detected).");
        config.addDefault("alt-ban.kick-message", altBanKickMessage);
    }

    // -------------------------------------------------------------------------
    // Chat filter settings
    // -------------------------------------------------------------------------

    public static boolean chatFilterEnabled = false;
    public static String chatFilterAction = "block";
    public static String chatFilterMessage = "Your message was blocked by the chat filter.";

    private static void chatFilter() {
        chatFilterEnabled = getBoolean("chat-filter.enabled", false);
        chatFilterAction = config.getString("chat-filter.action", "block");
        config.addDefault("chat-filter.action", chatFilterAction);
        chatFilterMessage = config.getString("chat-filter.message", "Your message was blocked by the chat filter.");
        config.addDefault("chat-filter.message", chatFilterMessage);
    }

    // -------------------------------------------------------------------------
    // Scoreboard settings
    // -------------------------------------------------------------------------

    public static boolean scoreboardEnabled = false;
    public static String scoreboardTitle = "&6&lMy Server";
    public static List<String> scoreboardLines = List.of(
            "&7&m                    ",
            "&fPlayer: &a{player}",
            "&fPing: &a{ping}ms",
            "",
            "&fKills: &c{kills}",
            "&fDeaths: &c{deaths}",
            "&fKD: &e{kd}",
            "",
            "&fBalance: &2${money}",
            "&7&m                    "
    );
    public static int scoreboardUpdateTicks = 20;

    private static void scoreboard() {
        scoreboardEnabled = getBoolean("scoreboard.enabled", false);
        scoreboardTitle = config.getString("scoreboard.title", "&6&lMy Server");
        config.addDefault("scoreboard.title", scoreboardTitle);
        scoreboardLines = config.getStringList("scoreboard.lines");
        if (scoreboardLines.isEmpty()) {
            scoreboardLines = List.of(
                    "&7&m                    ",
                    "&fPlayer: &a{player}",
                    "&fPing: &a{ping}ms",
                    "",
                    "&fKills: &c{kills}",
                    "&fDeaths: &c{deaths}",
                    "&fKD: &e{kd}",
                    "",
                    "&fBalance: &2${money}",
                    "&7&m                    "
            );
        }
        config.addDefault("scoreboard.lines", scoreboardLines);
        scoreboardUpdateTicks = getInt("scoreboard.update-ticks", 20);
    }

    // -------------------------------------------------------------------------
    // GrimAC embedded anticheat
    // -------------------------------------------------------------------------

    public static boolean grimEnabled = false;

    private static void grim() {
        grimEnabled = getBoolean("grim.enabled", false);
    }

    // -------------------------------------------------------------------------
    // RTP (Random Teleport) GUI settings
    // -------------------------------------------------------------------------

    public static boolean rtpEnabled = false;
    public static int rtpDelaySeconds = 5;
    public static double rtpCost = 0.0;
    public static int rtpMaxDistance = 10000;
    public static int rtpMinDistance = 500;
    public static boolean rtpOpOnly = false;
    public static boolean rtpDebug = false;
    public static int rtpCooldownSeconds = 0;

    // Raytrace block hiding (occlusion antixray)
    public static boolean raytraceHidingEnabled = false;
    public static List<String> raytraceTargetBlocks = List.of();
    public static int raytraceMaxRayDistance = 48;
    public static int raytraceChecksPerTick = 4;
    public static int raytraceSamplesPerBlock = 2;
    public static int raytraceRefreshSeconds = 120;
    public static int occlusionWorkerThreads = 2;

    // /tpa
    public static boolean tpaEnabled = false;
    public static int tpaTimeoutSeconds = 60;
    public static int tpaDelaySeconds = 3;
    public static int tpaCooldownSeconds = 0;
    public static boolean tpaDebug = false;

    // Signed chat / known packs enforcement
    public static boolean signedChatKick = false;
    public static String signedChatKickMessage = "Unsigned chat detected. Modified clients that block chat signing are not allowed.";
    public static boolean rejectModdedKnownPacks = false;
    public static String knownPacksKickMessage = "Modified clients are not allowed on this server.";

    // Entity occlusion
    public static boolean entityOcclusionEnabled = false;
    public static int entityOcclusionMaxDistance = 48;
    public static int entityOcclusionCheckIntervalTicks = 10;

    private static void rtp() {
        rtpEnabled = getBoolean("rtp.enabled", false);
        rtpDelaySeconds = getInt("rtp.delay-seconds", 5);
        rtpCost = config.getDouble("rtp.cost", 0.0);
        config.addDefault("rtp.cost", rtpCost);
        rtpMaxDistance = getInt("rtp.max-distance", 10000);
        rtpMinDistance = getInt("rtp.min-distance", 500);
        rtpOpOnly = getBoolean("rtp.op-only", false);
        rtpDebug = getBoolean("rtp.debug", false);
        rtpCooldownSeconds = getInt("rtp.cooldown-seconds", 60);
        config.addDefault("rtp.cooldown-seconds", rtpCooldownSeconds);
    }

    // -------------------------------------------------------------------------
    // Discord webhook settings
    // -------------------------------------------------------------------------

    public static boolean discordWebhookEnabled = false;
    public static String discordWebhookUrl = "";
    public static List<String> discordWebhookPingOnStop = List.of();

    private static void discordWebhook() {
        discordWebhookEnabled = getBoolean("discord-webhook.enabled", false);
        discordWebhookUrl = config.getString("discord-webhook.url", "");
        config.addDefault("discord-webhook.url", discordWebhookUrl);
        discordWebhookPingOnStop = config.getStringList("discord-webhook.ping-on-stop");
        config.addDefault("discord-webhook.ping-on-stop", List.of());
    }

    // -------------------------------------------------------------------------
    // Team settings
    // -------------------------------------------------------------------------

    public static boolean teamEnabled = false;
    public static boolean teamDebug = false;
    public static int teamMaxSize = 4;
    public static int teamInviteTimeoutSeconds = 60;

    private static void team() {
        teamEnabled = getBoolean("team.enabled", false);
        teamDebug = getBoolean("team.debug", false);
        teamMaxSize = getInt("team.max-size", 4);
        teamInviteTimeoutSeconds = getInt("team.invite-timeout-seconds", 60);
    }
}
