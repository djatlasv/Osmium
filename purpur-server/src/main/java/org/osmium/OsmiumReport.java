package org.osmium;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.osmium.discord.OsmiumDiscordBot;

import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /report — send a player report to the Discord staff channel.
 *
 * Flow: /report <player> <reason> validates the target is online, applies a
 * per-player cooldown, then pushes an embed (reporter, reported, reason,
 * report id) to the Discord report channel with three action buttons:
 * Dismiss / Time ban (report.time-ban-hours) / Ban (permanent).
 *
 * Buttons are handled by OsmiumDiscordBot; the mod-tier permission is
 * re-checked at click time. Reports persist to osmium-reports.json so
 * buttons keep working across restarts; entries older than 7 days are
 * pruned on load. The ban itself runs on the main thread (see banPlayer).
 */
public class OsmiumReport {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Report");
    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting().create();
    private static final Type STORE_TYPE =
            new com.google.gson.reflect.TypeToken<Map<String, Report>>() {}.getType();
    private static final long PRUNE_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_REASON_LENGTH = 256;

    public record Report(UUID id, String reporterName, UUID reporterUuid,
                         String targetName, UUID targetUuid,
                         String reason, long createdAt,
                         String status, String resolvedBy, long resolvedAt) {}

    private static volatile File storeFile;
    private static final Map<UUID, Report> REPORTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_USE = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public static void init(File serverDir) {
        storeFile = new File(serverDir, "osmium-reports.json");
        REPORTS.clear();
        if (!storeFile.exists()) return;
        try (var reader = new InputStreamReader(new java.io.FileInputStream(storeFile), StandardCharsets.UTF_8)) {
            Map<String, Report> loaded = GSON.fromJson(reader, STORE_TYPE);
            if (loaded == null) return;
            long cutoff = System.currentTimeMillis() - PRUNE_AGE_MS;
            boolean pruned = false;
            for (Report r : loaded.values()) {
                if (r == null || r.id() == null) continue;
                if (r.createdAt() < cutoff) { pruned = true; continue; }
                REPORTS.put(r.id(), r);
            }
            if (pruned) save();
        } catch (Exception e) {
            LOGGER.error("Failed to load osmium-reports.json", e);
        }
    }

    private static void save() {
        File file = storeFile;
        if (file == null) return;
        try {
            Map<String, Report> out = new LinkedHashMap<>();
            for (Map.Entry<UUID, Report> e : REPORTS.entrySet()) {
                out.put(e.getKey().toString(), e.getValue());
            }
            try (var writer = new OutputStreamWriter(new java.io.FileOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(out, writer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to save osmium-reports.json", e);
        }
    }

    // ------------------------------------------------------------------
    // Command registration
    // ------------------------------------------------------------------

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Always register — config may not be loaded yet at registration time.
        // Enabled check happens at execution time.
        dispatcher.register(
                Commands.literal("report")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            if (!enabled()) return 0;
                                            submit(player, StringArgumentType.getString(ctx, "player"),
                                                    StringArgumentType.getString(ctx, "reason"));
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    if (!enabled()) return 0;
                                    usage(player);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!enabled()) return 0;
                            usage(player);
                            return 1;
                        })
        );
    }

    private static boolean enabled() {
        return OsmiumConfig.reportEnabled;
    }

    private static void usage(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("\u00a7eUsage: /report <player> <reason>"));
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private static void submit(ServerPlayer from, String targetNameRaw, String reasonRaw) {
        String reason = reasonRaw == null ? "" : reasonRaw.trim();
        if (reason.isEmpty()) { usage(from); return; }
        if (reason.length() > MAX_REASON_LENGTH) reason = reason.substring(0, MAX_REASON_LENGTH);

        ServerPlayer target = findOnline(targetNameRaw);
        if (target == null) {
            from.sendSystemMessage(Component.literal("\u00a7cPlayer '" + targetNameRaw + "' is not online."));
            return;
        }
        if (target.getUUID().equals(from.getUUID())) {
            from.sendSystemMessage(Component.literal("\u00a7cYou can't report yourself."));
            return;
        }

        // Cooldown between outgoing reports
        int cooldown = Math.max(0, OsmiumConfig.reportCooldownSeconds);
        if (cooldown > 0) {
            long now = System.currentTimeMillis();
            Long last = LAST_USE.get(from.getUUID());
            if (last != null && now - last < cooldown * 1000L) {
                long remaining = cooldown - (now - last) / 1000L;
                from.sendSystemMessage(Component.literal(
                        "\u00a7cYou can use /report again in \u00a7e" + remaining + "s\u00a7c."));
                return;
            }
            LAST_USE.put(from.getUUID(), now);
        }

        Report report = new Report(UUID.randomUUID(),
                from.getGameProfile().name(), from.getUUID(),
                target.getGameProfile().name(), target.getUUID(),
                reason, System.currentTimeMillis(), "open", "", 0);
        REPORTS.put(report.id(), report);
        save();

        boolean sent = OsmiumDiscordBot.pushReport(report);
        if (sent) {
            from.sendSystemMessage(Component.literal("\u00a7aReport sent to the staff. Thanks!"));
            LOGGER.info("[Report] {} reported {}: {}",
                    from.getGameProfile().name(), target.getGameProfile().name(), reason);
        } else {
            // Bot offline / no report channel: don't keep a report nobody can act on
            REPORTS.remove(report.id());
            save();
            from.sendSystemMessage(Component.literal(
                    "\u00a7cReports are currently unavailable — try again later."));
        }
    }

    private static ServerPlayer findOnline(String name) {
        for (ServerPlayer p : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    /** Shared ban entry point (main thread): used by report buttons and the Discord /ban command. */
    public static void banPlayer(String playerName, UUID fallbackUuid, String reason,
                                 Date expires, String source) {
        MinecraftServer server = MinecraftServer.getServer();
        server.execute(() -> {
            try {
                var resolved = server.services().nameToIdCache().get(playerName);
                net.minecraft.server.players.NameAndId nameAndId = resolved.orElse(null);
                if (nameAndId == null) {
                    nameAndId = new net.minecraft.server.players.NameAndId(
                            fallbackUuid != null ? fallbackUuid
                                    : UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8)),
                            playerName);
                }
                var entry = new net.minecraft.server.players.UserBanListEntry(nameAndId, null, source, expires, reason);
                server.getPlayerList().getBans().add(entry);
                var online = server.getPlayerList().getPlayer(nameAndId.id());
                if (online != null) {
                    online.connection.disconnect(Component.literal("Banned: " + reason),
                            org.bukkit.event.player.PlayerKickEvent.Cause.BANNED);
                }
            } catch (Exception ex) {
                LOGGER.error("Ban failed for {}", playerName, ex);
            }
        });
    }

    // ------------------------------------------------------------------
    // Store access (used by the Discord bot's report buttons)
    // ------------------------------------------------------------------

    public static Report get(UUID id) {
        return REPORTS.get(id);
    }

    public static boolean isOpen(Report r) {
        return r != null && "open".equals(r.status());
    }

    /** Marks a report handled; returns the updated report or null if unknown. */
    public static Report resolve(UUID id, String status, String actor) {
        Report r = REPORTS.get(id);
        if (r == null) return null;
        Report updated = new Report(r.id(), r.reporterName(), r.reporterUuid(),
                r.targetName(), r.targetUuid(), r.reason(), r.createdAt(),
                status, actor, System.currentTimeMillis());
        REPORTS.put(id, updated);
        save();
        return updated;
    }
}
