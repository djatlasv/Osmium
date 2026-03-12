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
import java.util.logging.Level;

public class OsmiumConfig {

    private static final String HEADER = "Osmium Configuration\n"
            + "Osmium is a custom Purpur fork with native anticheat and security features.\n"
            + "\n"
            + "GitHub: https://github.com/djatlasv/Osmium\n";

    public static File CONFIG_FILE;
    public static YamlConfiguration config;
    public static int version = 1;

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

    public static boolean chunkHidingEnabled = true;
    public static int chunkHidingYThreshold = 0;
    public static int chunkHidingProximityRadius = 32;

    private static void chunkHiding() {
        chunkHidingEnabled = getBoolean("chunk-hiding.enabled", true);
        chunkHidingYThreshold = getInt("chunk-hiding.y-threshold", 0);
        chunkHidingProximityRadius = getInt("chunk-hiding.proximity-radius", 32);
    }

    // -------------------------------------------------------------------------
    // Y-level hiding settings
    // -------------------------------------------------------------------------

    public static boolean yLevelHidingEnabled = false;
    public static int yLevelHidingThreshold = -32;

    private static void yLevelHiding() {
        yLevelHidingEnabled = getBoolean("y-level-hiding.enabled", false);
        yLevelHidingThreshold = getInt("y-level-hiding.threshold", -32);
    }

    // -------------------------------------------------------------------------
    // Brand enforcement settings
    // -------------------------------------------------------------------------

    public static boolean brandEnforcementEnabled = false;
    public static String brandEnforcementKickMessage = "You must use the HandShaker mod. Get it at: discord.gg/yourserver";

    private static void brandEnforcement() {
        brandEnforcementEnabled = getBoolean("brand-enforcement.enabled", false);
        brandEnforcementKickMessage = config.getString("brand-enforcement.kick-message",
                "You must use the HandShaker mod. Get it at: discord.gg/yourserver");
        config.addDefault("brand-enforcement.kick-message", brandEnforcementKickMessage);
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
}
