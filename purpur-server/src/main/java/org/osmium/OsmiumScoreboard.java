package org.osmium;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet-based sidebar scoreboard. Never touches the player's actual
 * scoreboard assignment, so the tab list and teams stay intact.
 *
 * Placeholders:
 *   {player} {ping} {kills} {deaths} {kd} {money} {online} {max} {tps}
 */
public class OsmiumScoreboard {

    private static final String OBJECTIVE_NAME = "osmium_sb";
    // Track previous lines per player so we can remove stale entries
    private static final Map<UUID, List<String>> previousLines = new ConcurrentHashMap<>();
    private static Object economy = null;
    private static java.lang.reflect.Method getBalanceMethod = null;
    private static boolean vaultChecked = false;

    // Shared dummy scoreboard + objective for packet construction only
    private static final Scoreboard DUMMY_BOARD = new Scoreboard();
    private static Objective dummyObjective;

    /**
     * Called every N ticks from MinecraftServer.tickChildren().
     */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (!OsmiumConfig.scoreboardEnabled) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            update(player);
        }
    }

    /**
     * Sets up the scoreboard for a player on join.
     */
    public static void onJoin(ServerPlayer player) {
        if (!OsmiumConfig.scoreboardEnabled) return;
        sendCreate(player);
        update(player);
    }

    public static void onQuit(UUID uuid) {
        previousLines.remove(uuid);
    }

    private static Objective getDummyObjective(String title) {
        // Recreate if title changed
        if (dummyObjective != null) {
            DUMMY_BOARD.removeObjective(dummyObjective);
        }
        dummyObjective = DUMMY_BOARD.addObjective(
                OBJECTIVE_NAME,
                ObjectiveCriteria.DUMMY,
                Component.literal(colorize(title)),
                ObjectiveCriteria.RenderType.INTEGER,
                true, // auto update
                BlankFormat.INSTANCE
        );
        return dummyObjective;
    }

    private static void sendCreate(ServerPlayer player) {
        Objective obj = getDummyObjective(OsmiumConfig.scoreboardTitle);

        // Create objective (method 0)
        player.connection.send(new ClientboundSetObjectivePacket(obj, ClientboundSetObjectivePacket.METHOD_ADD));

        // Display in sidebar
        player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, obj));
    }

    private static void update(ServerPlayer player) {
        UUID uuid = player.getUUID();
        List<String> oldLines = previousLines.getOrDefault(uuid, Collections.emptyList());

        // Build new lines
        List<String> lines = OsmiumConfig.scoreboardLines;
        List<String> newEntries = new ArrayList<>(lines.size());

        for (int i = 0; i < lines.size(); i++) {
            String line = replacePlaceholders(lines.get(i), player);
            line = colorize(line);

            // Empty lines need unique invisible content
            if (line.isEmpty() || line.isBlank()) {
                line = "\u00a7r" + " ".repeat(i);
            }

            newEntries.add(line);
        }

        // Update title
        Objective obj = getDummyObjective(OsmiumConfig.scoreboardTitle);
        player.connection.send(new ClientboundSetObjectivePacket(obj, ClientboundSetObjectivePacket.METHOD_CHANGE));

        // Remove old entries that aren't in the new set
        for (String oldEntry : oldLines) {
            if (!newEntries.contains(oldEntry)) {
                player.connection.send(new ClientboundResetScorePacket(oldEntry, OBJECTIVE_NAME));
            }
        }

        // Send new scores
        for (int i = 0; i < newEntries.size(); i++) {
            String entry = newEntries.get(i);
            int score = newEntries.size() - i;

            player.connection.send(new ClientboundSetScorePacket(
                    entry,              // "player" name (visible line text)
                    OBJECTIVE_NAME,
                    score,
                    Optional.of(Component.literal(entry)),
                    Optional.of(BlankFormat.INSTANCE) // hide the number
            ));
        }

        previousLines.put(uuid, newEntries);
    }

    private static String replacePlaceholders(String line, ServerPlayer player) {
        org.bukkit.entity.Player bukkit = player.getBukkitEntity();

        line = line.replace("{player}", player.getPlainTextName());
        line = line.replace("{ping}", String.valueOf(player.connection.latency()));
        line = line.replace("{online}", String.valueOf(org.bukkit.Bukkit.getOnlinePlayers().size()));
        line = line.replace("{max}", String.valueOf(org.bukkit.Bukkit.getMaxPlayers()));

        int kills = bukkit.getStatistic(org.bukkit.Statistic.PLAYER_KILLS);
        int deaths = bukkit.getStatistic(org.bukkit.Statistic.DEATHS);
        double kd = deaths == 0 ? kills : Math.round((double) kills / deaths * 100.0) / 100.0;
        line = line.replace("{kills}", String.valueOf(kills));
        line = line.replace("{deaths}", String.valueOf(deaths));
        line = line.replace("{kd}", String.format("%.2f", kd));

        double tps = Math.min(20.0, org.bukkit.Bukkit.getTPS()[0]);
        line = line.replace("{tps}", String.format("%.1f", tps));

        line = line.replace("{money}", getBalance(bukkit));

        return line;
    }

    private static String getBalance(org.bukkit.entity.Player player) {
        // Retry until economy is found — plugins may register late
        if (economy == null) {
            try {
                Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
                org.bukkit.plugin.RegisteredServiceProvider<?> rsp =
                        org.bukkit.Bukkit.getServicesManager().getRegistration(economyClass);
                if (rsp != null) {
                    economy = rsp.getProvider();
                    getBalanceMethod = economyClass.getMethod("getBalance", org.bukkit.OfflinePlayer.class);
                }
            } catch (ClassNotFoundException e) {
                // Vault not installed — stop trying
                vaultChecked = true;
            } catch (Exception ignored) {}
        }
        if (economy != null && getBalanceMethod != null) {
            try {
                Object result = getBalanceMethod.invoke(economy, (org.bukkit.OfflinePlayer) player);
                double bal = ((Number) result).doubleValue();
                return String.format("%.2f", bal);
            } catch (Exception e) {
                // Try the deprecated String name method as fallback
                try {
                    java.lang.reflect.Method nameMethod = economy.getClass().getMethod("getBalance", String.class);
                    Object result = nameMethod.invoke(economy, player.getName());
                    double bal = ((Number) result).doubleValue();
                    return String.format("%.2f", bal);
                } catch (Exception ignored) {}
                org.bukkit.Bukkit.getLogger().warning("[Osmium] Vault getBalance failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return "0.00";
    }

    private static String colorize(String text) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', text);
    }
}
