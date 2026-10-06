package org.osmium.anticheat;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;

/**
 * Deferred signed-chat session check.
 *
 * In the 26.3 base the chat session arrives as a play-phase packet right
 * after join, so ServerPlayer.getChatSession() is ALWAYS null while
 * PlayerList.placeNewPlayer runs — an immediate check kicks every client,
 * vanilla included. Privacy mods that strip key exchange never establish a
 * session at all, so the check is deferred by a grace window (same pattern
 * as brand enforcement) and only kicks players whose session never arrives.
 */
public final class OsmiumSignedChat {

    private static final Map<UUID, Integer> scheduledChecks = new ConcurrentHashMap<>();

    private OsmiumSignedChat() {
    }

    public static void scheduleCheck(UUID playerUuid, int checkAtTick) {
        scheduledChecks.put(playerUuid, checkAtTick);
    }

    public static void cancel(UUID playerUuid) {
        scheduledChecks.remove(playerUuid);
    }

    /**
     * Called every tick from MinecraftServer.tickChildren().
     */
    public static void tick(int currentTick, MinecraftServer server) {
        if (scheduledChecks.isEmpty()) return;
        if (!org.osmium.OsmiumConfig.signedChatKick || !server.enforceSecureProfile()) {
            scheduledChecks.clear();
            return;
        }

        Iterator<Map.Entry<UUID, Integer>> it = scheduledChecks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            if (currentTick < entry.getValue()) continue;
            it.remove();
            UUID uuid = entry.getKey();

            // Player may have disconnected before the grace window elapsed
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || player.connection == null || !player.connection.isAcceptingMessages()) {
                continue;
            }

            if (player.getChatSession() == null) {
                Bukkit.getLogger().info("[Osmium] " + player.getPlainTextName() + " joined without a chat signing session — kicking");
                player.connection.disconnect(net.minecraft.network.chat.Component.literal(
                        org.osmium.OsmiumConfig.signedChatKickMessage));
            }
        }
    }
}
