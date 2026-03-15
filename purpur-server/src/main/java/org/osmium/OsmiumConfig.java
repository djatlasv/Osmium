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
            + "  proximity-radius: how many blocks around the player to reveal real blocks\n"
            + "\n"
            + "--- brand-enforcement ---\n"
            + "Enforces client mod rules using the HandShaker protocol at the NMS level.\n"
            + "  enabled: master toggle\n"
            + "  mode: 'strict' requires all clients to have HandShaker, 'vanilla' allows vanilla clients\n"
            + "  kick-message: message shown when a player is kicked for mod violations\n"
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
            + "  url: your Discord webhook URL\n";

    public static File CONFIG_FILE;
    public static YamlConfiguration config;
    public static int version = 2;

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

        set("config-version", version);
        readConfig(OsmiumConfig.class, null);
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

    private static void chunkHiding() {
        chunkHidingEnabled = getBoolean("chunk-hiding.enabled", false);
        chunkHidingYThreshold = getInt("chunk-hiding.y-threshold", 0);
        chunkHidingBlock = config.getString("chunk-hiding.block", "deepslate");
        config.addDefault("chunk-hiding.block", chunkHidingBlock);
        chunkHidingProximityRadius = getInt("chunk-hiding.proximity-radius", 32);
    }

    // -------------------------------------------------------------------------
    // Brand enforcement settings
    // -------------------------------------------------------------------------

    public static boolean brandEnforcementEnabled = false;
    public static String brandEnforcementMode = "vanilla";
    public static String brandEnforcementKickMessage = "You must use the HandShaker mod. Get it at: discord.gg/yourserver";
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

    private static void discordWebhook() {
        discordWebhookEnabled = getBoolean("discord-webhook.enabled", false);
        discordWebhookUrl = config.getString("discord-webhook.url", "");
        config.addDefault("discord-webhook.url", discordWebhookUrl);
    }
}
