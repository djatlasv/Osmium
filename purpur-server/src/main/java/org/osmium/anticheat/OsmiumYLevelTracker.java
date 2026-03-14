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
 * Tracks player positions and resends nearby chunks when they move near the
 * hidden zone so that the proximity-based reveal/re-hide stays in sync.
 *
 * Only resends chunks near the player's old or new position — not all chunks.
 * This keeps large proximity-radius values viable without flooding the connection.
 */
public class OsmiumYLevelTracker {

    private static final Map<UUID, long[]> lastPosition = new ConcurrentHashMap<>();

    public static void onPlayerTick(ServerPlayer player) {
        if (!OsmiumConfig.chunkHidingEnabled) return;

        UUID uuid = player.getUUID();
        int blockX = player.blockPosition().getX();
        int blockY = player.blockPosition().getY();
        int blockZ = player.blockPosition().getZ();
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        int sectionY = blockY >> 4;

        long[] prev = lastPosition.get(uuid);
        if (prev != null && prev[0] == chunkX && prev[1] == chunkZ && prev[2] == sectionY) {
            return; // no chunk/section change
        }

        long[] current = new long[] { chunkX, chunkZ, sectionY };
        lastPosition.put(uuid, current);

        if (prev == null) return;

        int thresholdSection = OsmiumConfig.chunkHidingYThreshold >> 4;
        int proximityRadius = OsmiumConfig.chunkHidingProximityRadius;

        boolean wasNear = isNearHiddenZone((int) prev[2], thresholdSection, proximityRadius);
        boolean isNear = isNearHiddenZone(sectionY, thresholdSection, proximityRadius);

        boolean shouldResend = false;

        if (wasNear != isNear) {
            // Crossed the near/far boundary
            shouldResend = true;
        } else if (isNear && (chunkX != prev[0] || chunkZ != prev[1] || sectionY != prev[2])) {
            // Moved to a different chunk while near the zone
            shouldResend = true;
        }

        if (shouldResend) {
            resendNearbyChunks(player, (int) prev[0], (int) prev[1], (int) prev[2],
                                       chunkX, chunkZ, sectionY, proximityRadius);
        }
    }

    public static void onPlayerDisconnect(UUID uuid) {
        lastPosition.remove(uuid);
    }

    private static boolean isNearHiddenZone(int playerSectionY, int thresholdSection, int proximityRadius) {
        int hiddenTopBlockY = (thresholdSection << 4) - 1;
        int playerBlockY = playerSectionY << 4;
        return playerBlockY <= hiddenTopBlockY + proximityRadius;
    }

    /**
     * Only resend chunks that are near the player's old or new position.
     * These are the only chunks whose hidden/revealed status could have changed.
     */
    private static void resendNearbyChunks(ServerPlayer player,
                                            int oldChunkX, int oldChunkZ, int oldSectionY,
                                            int newChunkX, int newChunkZ, int newSectionY,
                                            int proximityRadius) {
        ServerLevel level = player.level();
        RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
                ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer) player).moonrise$getChunkLoader();
        LongOpenHashSet sentChunks = loader.getSentChunksRaw();

        // Chunks within this many chunks of the player could have changed status
        int chunkRadius = (proximityRadius >> 4) + 2;

        for (long chunkKey : sentChunks) {
            int cx = (int) chunkKey;
            int cz = (int) (chunkKey >> 32);

            // Only resend if this chunk is near the old OR new player position
            boolean nearOld = Math.abs(cx - oldChunkX) <= chunkRadius
                           && Math.abs(cz - oldChunkZ) <= chunkRadius;
            boolean nearNew = Math.abs(cx - newChunkX) <= chunkRadius
                           && Math.abs(cz - newChunkZ) <= chunkRadius;

            if (!nearOld && !nearNew) continue;

            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) {
                PlayerChunkSender.sendChunk(player.connection, level, chunk);
            }
        }
    }
}
