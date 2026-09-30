package org.osmium.anticheat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.osmium.OsmiumConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Warns players whose connection is consistently laggy: sustained average
 * ping over the configured threshold triggers a chat (+ optional action
 * bar) warning. High latency makes position corrections look like
 * movement cheats (speed / killaura) to GrimAC, so the warning explicitly
 * tells the player they may be falsely banned.
 *
 * Ping source: Paper's keepalive 5s-average (player.connection.latency()).
 * Sustain window resets as soon as ping drops back under the threshold, so
 * a single spike never warns.
 *
 * Driven from OsmiumOcclusion.tick (already called every tick by the NMS
 * patch); giving it its own tickChildren hook is a follow-up cleanup.
 */
public final class OsmiumLatencyWarn {

    private OsmiumLatencyWarn() {}

    /** uuid -> millis when the sustained high-ping window began */
    private static final Map<UUID, Long> HIGH_SINCE = new ConcurrentHashMap<>();
    /** uuid -> millis of the last warning sent */
    private static final Map<UUID, Long> LAST_WARN = new ConcurrentHashMap<>();

    public static void tick(MinecraftServer server) {
        if (!OsmiumConfig.latencyWarnEnabled) return;

        long now = System.currentTimeMillis();
        long sustainMs = Math.max(1, OsmiumConfig.latencyWarnSustainSeconds) * 1000L;
        long warnEveryMs = Math.max(5, OsmiumConfig.latencyWarnIntervalSeconds) * 1000L;
        int threshold = OsmiumConfig.latencyWarnThresholdMs;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            int ping = p.connection.latency();
            if (ping >= threshold) {
                long since = HIGH_SINCE.computeIfAbsent(id, k -> now);
                if (now - since >= sustainMs) {
                    Long last = LAST_WARN.get(id);
                    if (last == null || now - last >= warnEveryMs) {
                        LAST_WARN.put(id, now);
                        warn(p, ping);
                    }
                }
            } else {
                HIGH_SINCE.remove(id); // recovered: restart the sustain window next time
            }
        }

        // Prune entries for players who are gone (login storms, kicks).
        HIGH_SINCE.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        LAST_WARN.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    private static void warn(ServerPlayer player, int ping) {
        String msg = OsmiumConfig.latencyWarnMessage.replace("{ping}", String.valueOf(ping));
        player.sendSystemMessage(Component.literal(msg));
        if (OsmiumConfig.latencyWarnActionBar) {
            player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(msg)));
        }
    }
}