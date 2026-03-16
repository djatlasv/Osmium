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
            + "Tracks IP-to-UUID associations. Auto-bans alt accounts sharing an IP with a banned player.\n"
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
            + "--- grim ---\n"
            + "Runs GrimAC 2.3.74 as a native server module instead of a plugin.\n"
            + "GrimAC configs live in the ./grim/ directory.\n"
            + "  enabled: master toggle\n"
            + "\n"
            + "--- discord-webhook ---\n"
            + "Sends Discord embed notifications for server events (start/stop, bans, alts, etc.).\n"
            + "  enabled: master toggle\n"
            + "  url: your Discord webhook URL\n"
            + "  ping-on-stop: list of Discord user IDs to ping when the server stops (e.g. ['123456789'])\n";

    public static File CONFIG_FILE;
    public static YamlConfiguration config;
    public static int version = 4;

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

    private static void chunkHiding() {
        chunkHidingEnabled = getBoolean("chunk-hiding.enabled", false);
        chunkHidingYThreshold = getInt("chunk-hiding.y-threshold", 0);
        chunkHidingBlock = config.getString("chunk-hiding.block", "deepslate");
        config.addDefault("chunk-hiding.block", chunkHidingBlock);
        chunkHidingProximityRadius = getInt("chunk-hiding.proximity-radius", 32);
        chunkHidingHideEntities = getBoolean("chunk-hiding.hide-entities", true);
    }

    // -------------------------------------------------------------------------
    // Brand enforcement settings
    // -------------------------------------------------------------------------

    public static boolean brandEnforcementEnabled = false;
    public static String brandEnforcementMode = "vanilla";
    public static String brandEnforcementKickMessage = "You must use the HandShaker mod. Get it at: discord.gg/yourserver";
    public static String brandEnforcementBlacklistKickMessage = "You have been kicked for using a blacklisted mod: {mods}";
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
        brandEnforcementCheckDelayTicks = getInt("brand-enforcement.check-delay-ticks", 100);
        brandEnforcementRequiredMods = config.getStringList("brand-enforcement.required-mods");
        config.addDefault("brand-enforcement.required-mods", List.of());
        brandEnforcementBlacklistedMods = config.getStringList("brand-enforcement.blacklisted-mods");
        config.addDefault("brand-enforcement.blacklisted-mods", List.of());
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
    // GrimAC embedded anticheat
    // -------------------------------------------------------------------------

    public static boolean grimEnabled = false;

    private static void grim() {
        grimEnabled = getBoolean("grim.enabled", false);
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
}
