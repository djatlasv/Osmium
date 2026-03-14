package org.osmium.anticheat;

import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.chunk.LevelChunk;
import org.osmium.OsmiumConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player Y positions and resends chunks when they cross the
 * proximity boundary of the hidden zone — both entering (to reveal)
 * and leaving (to re-hide).
 */
public class OsmiumYLevelTracker {

    private static final Map<UUID, Integer> lastSectionY = new ConcurrentHashMap<>();

    public static void onPlayerTick(ServerPlayer player) {
        if (!OsmiumConfig.chunkHidingEnabled) return;

        UUID uuid = player.getUUID();
        int currentSection = player.blockPosition().getY() >> 4;
        Integer previous = lastSectionY.put(uuid, currentSection);

        if (previous == null || previous == currentSection) return;

        int thresholdSection = OsmiumConfig.chunkHidingYThreshold >> 4;
        int proximityRadius = OsmiumConfig.chunkHidingProximityRadius;

        boolean wasNear = isNearHiddenZone(previous, thresholdSection, proximityRadius);
        boolean isNear = isNearHiddenZone(currentSection, thresholdSection, proximityRadius);

        // Resend when crossing the boundary in either direction:
        // far→near = reveal real blocks, near→far = re-hide with replacement
        if (wasNear != isNear) {
            resendChunks(player);
        }
    }

    public static void onPlayerDisconnect(UUID uuid) {
        lastSectionY.remove(uuid);
    }

    private static boolean isNearHiddenZone(int playerSectionY, int thresholdSection, int proximityRadius) {
        int hiddenTopBlockY = (thresholdSection << 4) - 1;
        int playerBlockY = playerSectionY << 4;
        return playerBlockY <= hiddenTopBlockY + proximityRadius;
    }

    private static void resendChunks(ServerPlayer player) {
        ServerLevel level = player.level();
        RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
                ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer) player).moonrise$getChunkLoader();
        LongOpenHashSet sentChunks = loader.getSentChunksRaw();

        for (long chunkKey : sentChunks) {
            int cx = (int) chunkKey;
            int cz = (int) (chunkKey >> 32);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) {
                PlayerChunkSender.sendChunk(player.connection, level, chunk);
            }
        }
    }
}
