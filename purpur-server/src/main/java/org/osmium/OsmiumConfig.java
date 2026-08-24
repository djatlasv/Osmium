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

    private static final String HEADER = "Osmium Configuration\nOsmium is a custom Purpur fork with native anticheat and security features.\nGitHub: https://github.com/djatlasv/Osmium\n\n======================================================================\n  ANTICHEAT & SECURITY\n======================================================================\n\n--- chunk-hiding ---\nReplaces all blocks below a Y level with a fake block in the chunk packet.\nPlayers within the proximity radius see real blocks (horizontal distance).\n  enabled: master toggle\n  y-threshold: blocks below this Y level are hidden\n  block: replacement block (e.g. deepslate)\n  hide-entities: also hide entities below the threshold from distant players\n  hide-light: zero out light data below the threshold (defeats Light Finder hacks)\n  proximity-radius: horizontal reveal radius around the player\n  shared-far-view-rewrites: cache rewritten buffers for distant viewers (experimental)\n\n--- raytrace-hiding ---\nRaycast ore hiding: protected blocks with no line of sight to any player are replaced.\n  enabled: master toggle (default false)\n  blocks: block ids to protect (default: diamond/emerald/gold/iron ore + ancient debris)\n  max-ray-distance: LOS ray length in blocks\n  checks-per-tick: max chunk recomputations queued per tick\n  samples-per-block: rays cast per block (1-4)\n  refresh-seconds: periodic visibility recalculation\n  worker-threads: background compute threads\n\n--- entity-occlusion ---\nHides entities and block entities (chests, ...) occluded by terrain.\n  enabled: master toggle (default false)\n  max-distance: beyond this, normal distance culling applies\n  check-interval-ticks: how often each entity+player pair is retraced\n\n--- brand-enforcement ---\nEnforces client mod rules using the HandShaker protocol.\n  enabled: master toggle\n  mode: 'strict' requires HandShaker; 'vanilla' allows vanilla clients\n  kick-message: shown when kicked for missing HandShaker/required mods\n  blacklist-kick-message: shown for blacklisted mods ({mods} = detected mods)\n  check-delay-ticks: ticks to wait for the HandShaker payload (100 = 5s)\n  require-osmium-handshaker: reject stock HandShaker (fingerprint needed)\n  required-mods: mod ids that must be installed\n  blacklisted-mods: mod ids that trigger a kick\n\n--- identity-enforcement ---\nDetects spoofed/vanilla-pretending clients without trusting their mod list.\n  signed-chat-kick: kick clients with signing keys that send unsigned chat or never establish a session (requires enforce-secure-profile=true)\n  signed-chat-kick-message: custom kick message\n  reject-modded-known-packs: reject clients reporting non-vanilla known packs + version validation (skipped when ViaVersion installed)\n  known-packs-kick-message: custom kick message\n\n--- alt-ban ---\nAlt detection via IP and hardware fingerprint associations.\nIP always works; fingerprints require brand-enforcement (HandShaker).\n  enabled: master toggle\n  kick-message: message shown when an alt is kicked\n\n--- chat-filter ---\nChat word-list filter (osmium-words.json). Exact + regex patterns.\n  enabled: master toggle\n  action: block | kick | mute\n  message: message shown when filtered\n\n--- grim ---\nAuto-installs GrimAC from Modrinth into plugins/ on first start.\n  enabled: master toggle\n\n======================================================================\n  GAMEPLAY\n======================================================================\n\n--- rtp ---\nRandom Teleport GUI (/rtp), charges economy via Vault if configured.\n  enabled: master toggle\n  delay-seconds: countdown (moving cancels)\n  cost: economy charge (0 = free)\n  max-distance / min-distance: radius range from world center\n  cooldown-seconds: between uses (0 = none)\n  op-only: restrict to ops\n  debug: verbose logging\n\n--- tpa ---\nTeleport requests: /tpa <player>, accept via clickable chat buttons or /tpaccept [player].\n  enabled: master toggle\n  timeout-seconds: request expiry\n  delay-seconds: countdown (moving cancels)\n  cooldown-seconds: between outgoing requests (0 = none)\n  debug: verbose logging\n\n--- homes ---\nPersonal homes + world spawn teleport (/sethome /home /homes /spawn).\n  enabled: master toggle\n  max-per-player: home limit\n  delay-seconds: countdown (moving cancels)\n  debug: verbose logging\n\n--- team ---\nTeam system: /team opens GUI — create, invite via clickable chat buttons,\nfriendly fire toggle by leader, kick/disband. Persists to osmium-teams.json.\n  enabled: master toggle\n  max-size: members per team\n  invite-timeout-seconds: invite expiry\n  debug: verbose logging\n\n--- scoreboard ---\nCustom sidebar scoreboard with live placeholders ({player} {ping} {tps} {money} ...).\n  enabled: master toggle\n  title: scoreboard title (& color codes)\n  lines: list of lines\n  update-ticks: refresh rate (20 = 1 second)\n\n======================================================================\n  DISCORD & SERVER OPS\n======================================================================\n\n--- discord-webhook ---\nDiscord embed notifications (start/stop, bans, alts, chat filter).\n  enabled: master toggle\n  url: webhook URL\n  ping-on-stop: user IDs to ping on stop\n\n--- discord-bot ---\nFull Discord bot — run the server from Discord with slash commands.\nSetup: /osmium setup in Discord (first runner becomes owner), then\n/osmium perm-add <role> <viewer|helper|mod|admin>.\n  enabled: master toggle (default false)\n  token: bot token — KEEP SECRET, never commit\n  audit-channel-id: channel for action log\n  chat-channel-id: two-way Minecraft <-> Discord chat relay\n\n--- backups ---\nAutomatic zipped backups of worlds + root configs into backups/.\n  enabled: master toggle (default false)\n  interval-minutes: time between backups (min 5)\n  keep: generations to retain\n  directory: output folder\n  worlds: comma-separated world names (blank = auto-detect)\n\n";

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

        // Fixed, documented order — reflection order is JVM-dependent and
        // scattered the file into a random mess.
        chunkHiding();
        raytraceHiding();
        entityOcclusion();
        brandEnforcement();
        identityEnforcement();
        altBan();
        chatFilter();
        grim();
        rtp();
        tpa();
        homes();
        team();
        scoreboard();
        backups();
        discordWebhook();
        discordBot();

        try {
            config.save(CONFIG_FILE);
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Could not save osmium.yml", ex);
        }

        // Bukkit's YAML saver does not preserve insertion order; reorder the
        // serialized file into the documented layout.
        reorderYamlFile(CONFIG_FILE);
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
    public static boolean chunkHidingSharedRewrites = false;

    private static void chunkHiding() {
        chunkHidingEnabled = getBoolean("chunk-hiding.enabled", false);
        chunkHidingYThreshold = getInt("chunk-hiding.y-threshold", 0);
        chunkHidingBlock = config.getString("chunk-hiding.block", "deepslate");
        config.addDefault("chunk-hiding.block", chunkHidingBlock);
        chunkHidingProximityRadius = getInt("chunk-hiding.proximity-radius", 32);
        chunkHidingHideEntities = getBoolean("chunk-hiding.hide-entities", true);
        chunkHidingHideLight = getBoolean("chunk-hiding.hide-light", true);
        chunkHidingSharedRewrites = getBoolean("chunk-hiding.shared-far-view-rewrites", false);
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

    private static void discordBot() {
        discordBotEnabled = getBoolean("discord-bot.enabled", false);
        discordBotToken = config.getString("discord-bot.token", "");
        discordBotAuditChannelId = config.getString("discord-bot.audit-channel-id", "");
        discordBotChatChannelId = config.getString("discord-bot.chat-channel-id", "");
        if (discordBotChatChannelId != null && !discordBotChatChannelId.isBlank()
                && (discordBotToken == null || discordBotToken.isBlank())) {
            Bukkit.getLogger().warning("[Osmium] chat-channel-id set but no token — bridge disabled");
        }
        if (discordBotEnabled && (discordBotToken == null || discordBotToken.isBlank())) {
            Bukkit.getLogger().warning("[Osmium] discord-bot.enabled=true but no token set — bot will not start");
        }
    }

    private static void combat() {
        combatEnabled = getBoolean("combat.enabled", false);
        combatDurationSeconds = Math.max(3, getInt("combat.duration-seconds", 15));
        combatActionBar = getBoolean("combat.action-bar", true);
    }

    private static void recipes() {
        recipesEnabled = getBoolean("recipes.enabled", true);
    }

    private static void homes() {
        homesEnabled = getBoolean("homes.enabled", false);
        homesMaxPerPlayer = Math.max(1, getInt("homes.max-per-player", 3));
        homesDelaySeconds = Math.max(0, getInt("homes.delay-seconds", 3));
        homesDebug = getBoolean("homes.debug", false);
    }

    private static void spawnDimension() {
        spawnDimensionEnabled = getBoolean("spawn-dimension.enabled", false);
        spawnDimensionName = config.getString("spawn-dimension.name", "spawn").trim().toLowerCase(java.util.Locale.ROOT);
        if (!spawnDimensionName.matches("[a-z0-9_]+")) {
            Bukkit.getLogger().warning("[Osmium] spawn-dimension.name must be [a-z0-9_] — using 'spawn'");
            spawnDimensionName = "spawn";
        }
    }

    private static void backups() {
        backupsEnabled = getBoolean("backups.enabled", false);
        backupsIntervalMinutes = Math.max(5, getInt("backups.interval-minutes", 60));
        backupsKeep = Math.max(1, getInt("backups.keep", 8));
        backupsDirectory = config.getString("backups.directory", "backups");
        backupsWorlds = config.getString("backups.worlds", "");
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

    // Discord bot
    public static boolean discordBotEnabled = false;
    public static String discordBotToken = "";
    public static String discordBotAuditChannelId = "";
    public static String discordBotChatChannelId = "";

    // Combat tag
    public static boolean combatEnabled = false;
    public static int combatDurationSeconds = 15;
    public static boolean combatActionBar = true;

    // Custom recipes
    public static boolean recipesEnabled = true;

    // Homes
    public static boolean homesEnabled = false;
    public static int homesMaxPerPlayer = 3;
    public static int homesDelaySeconds = 3;
    public static boolean homesDebug = false;

    // Spawn dimension
    public static boolean spawnDimensionEnabled = false;
    public static String spawnDimensionName = "spawn";

    // Backups
    public static boolean backupsEnabled = false;
    public static int backupsIntervalMinutes = 60;
    public static int backupsKeep = 8;
    public static String backupsDirectory = "backups";
    public static String backupsWorlds = "";   // blank = auto-detect

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
    /**
     * Rewrites the saved yaml so top-level sections appear in documented order.
     * Unknown/extra sections are kept at the end; config-version goes last.
     */
    private static void reorderYamlFile(File file) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(file.toPath());
            List<String> preamble = new java.util.ArrayList<>();
            java.util.LinkedHashMap<String, List<String>> blocks = new java.util.LinkedHashMap<>();
            List<String> current = null;
            String currentKey = null;
            for (String line : lines) {
                boolean topLevel = !line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#");
                if (topLevel) {
                    currentKey = line.split(":")[0].trim();
                    current = blocks.computeIfAbsent(currentKey, k -> new java.util.ArrayList<>());
                    current.add(line);
                } else if (current != null) {
                    current.add(line);
                } else {
                    preamble.add(line);
                }
            }

            String[] order = {
                "chunk-hiding", "raytrace-hiding", "entity-occlusion", "brand-enforcement",
                "identity-enforcement", "alt-ban", "chat-filter", "grim", "spawn-dimension", "combat",
                "rtp", "tpa", "homes", "team", "scoreboard", "recipes",
                "discord-webhook", "discord-bot", "backups"
            };

            List<String> outText = new java.util.ArrayList<>();
            for (String h : HEADER.split("\\n")) {
                if (!h.isBlank()) outText.add("# " + h);
            }
            outText.add("");
            for (String key : order) {
                List<String> blk = blocks.remove(key);
                if (blk != null) { outText.addAll(blk); outText.add(""); }
            }
            List<String> cv = blocks.get("config-version");
            blocks.remove("config-version");
            for (var e2 : blocks.entrySet()) { outText.addAll(e2.getValue()); outText.add(""); }
            if (cv != null) { outText.addAll(cv); outText.add(""); }
            while (!outText.isEmpty() && outText.get(outText.size()-1).isBlank()) outText.remove(outText.size()-1);

            java.nio.file.Files.write(file.toPath(), outText);
        } catch (Exception ex) {
            Bukkit.getLogger().log(Level.WARNING, "Could not reorder osmium.yml", ex);
        }
    }

}

