package org.osmium;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom sidebar scoreboard with configurable lines and placeholders.
 *
 * Placeholders:
 *   {player}  - player name
 *   {ping}    - player ping in ms
 *   {kills}   - player kills statistic
 *   {deaths}  - player deaths statistic
 *   {kd}      - kill/death ratio
 *   {money}   - balance (Vault economy if available)
 *   {online}  - online player count
 *   {max}     - max player count
 *   {tps}     - server TPS
 */
public class OsmiumScoreboard {

    private static final String OBJECTIVE_NAME = "osmium_sb";
    private static final Map<UUID, Scoreboard> playerBoards = new ConcurrentHashMap<>();
    private static Object economy = null; // net.milkbowl.vault.economy.Economy (loaded via reflection)
    private static java.lang.reflect.Method getBalanceMethod = null;
    private static boolean vaultChecked = false;

    /**
     * Called every N ticks from MinecraftServer.tickChildren().
     */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (!OsmiumConfig.scoreboardEnabled) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            update(player.getBukkitEntity());
        }
    }

    /**
     * Sets up the scoreboard for a player on join.
     */
    public static void onJoin(ServerPlayer player) {
        if (!OsmiumConfig.scoreboardEnabled) return;
        Player bukkit = player.getBukkitEntity();
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        playerBoards.put(bukkit.getUniqueId(), board);
        bukkit.setScoreboard(board);
        update(bukkit);
    }

    /**
     * Cleans up on disconnect.
     */
    public static void onQuit(UUID uuid) {
        playerBoards.remove(uuid);
    }

    private static void update(Player player) {
        Scoreboard board = playerBoards.get(player.getUniqueId());
        if (board == null) return;

        // Remove old objective and recreate (cleanest way to update all lines)
        Objective old = board.getObjective(OBJECTIVE_NAME);
        if (old != null) old.unregister();

        String title = colorize(OsmiumConfig.scoreboardTitle);
        Objective objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = OsmiumConfig.scoreboardLines;

        for (int i = 0; i < lines.size(); i++) {
            String line = replacePlaceholders(lines.get(i), player);
            line = colorize(line);

            // Handle empty lines with unique invisible strings
            if (line.isEmpty() || line.isBlank()) {
                line = ChatColor.RESET.toString() + " ".repeat(i);
            }

            // Ensure uniqueness — append invisible chars if duplicate
            objective.getScore(line).setScore(lines.size() - i);
        }
    }

    private static String replacePlaceholders(String line, Player player) {
        line = line.replace("{player}", player.getName());
        line = line.replace("{ping}", String.valueOf(player.getPing()));
        line = line.replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()));
        line = line.replace("{max}", String.valueOf(Bukkit.getMaxPlayers()));

        int kills = player.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = player.getStatistic(Statistic.DEATHS);
        double kd = deaths == 0 ? kills : Math.round((double) kills / deaths * 100.0) / 100.0;
        line = line.replace("{kills}", String.valueOf(kills));
        line = line.replace("{deaths}", String.valueOf(deaths));
        line = line.replace("{kd}", String.format("%.2f", kd));

        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        line = line.replace("{tps}", String.format("%.1f", tps));

        line = line.replace("{money}", getBalance(player));

        return line;
    }

    private static String getBalance(Player player) {
        if (!vaultChecked) {
            vaultChecked = true;
            try {
                Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
                @SuppressWarnings("unchecked")
                org.bukkit.plugin.RegisteredServiceProvider<?> rsp =
                        Bukkit.getServicesManager().getRegistration(economyClass);
                if (rsp != null) {
                    economy = rsp.getProvider();
                    getBalanceMethod = economyClass.getMethod("getBalance", org.bukkit.OfflinePlayer.class);
                }
            } catch (Exception ignored) {}
        }
        if (economy != null && getBalanceMethod != null) {
            try {
                double bal = (double) getBalanceMethod.invoke(economy, player);
                return String.format("%.2f", bal);
            } catch (Exception ignored) {}
        }
        return "0.00";
    }

    private static String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
