package org.osmium.anticheat;

import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
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
 * Resends chunks when the player moves vertically near the hidden zone.
 *
 * Key design decisions:
 * - Only resend on VERTICAL movement (section Y change). Horizontal movement
 *   doesn't need resends because new chunks sent by the server already have
 *   the correct proximity check applied at send time.
 * - The player's own chunk (and immediate neighbors) are resent IMMEDIATELY
 *   when their section Y changes, preventing fall damage from stale data.
 * - Surrounding chunks are queued and drained at a limited rate.
 */
public class OsmiumYLevelTracker {

    private static final int QUEUED_CHUNKS_PER_TICK = 8;

    private static final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    private static class PlayerState {
        int sectionY;
        boolean initialized;
        final LongArrayFIFOQueue resendQueue = new LongArrayFIFOQueue();
    }

    public static void onPlayerTick(ServerPlayer player) {
        if (!OsmiumConfig.chunkHidingEnabled) return;

        UUID uuid = player.getUUID();
        PlayerState state = states.computeIfAbsent(uuid, k -> new PlayerState());

        // Drain queued surrounding chunks
        drainQueue(player, state);

        int sectionY = player.blockPosition().getY() >> 4;

        if (!state.initialized) {
            state.sectionY = sectionY;
            state.initialized = true;
            return;
        }

        // Only act on vertical section changes
        if (sectionY == state.sectionY) return;

        int oldSectionY = state.sectionY;
        state.sectionY = sectionY;

        int thresholdSection = OsmiumConfig.chunkHidingYThreshold >> 4;
        int proximityRadius = OsmiumConfig.chunkHidingProximityRadius;

        boolean wasNear = isNearHiddenZone(oldSectionY, thresholdSection, proximityRadius);
        boolean isNear = isNearHiddenZone(sectionY, thresholdSection, proximityRadius);

        // Only resend if the player is near or was near the hidden zone
        if (!wasNear && !isNear) return;

        ServerLevel level = player.level();
        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;

        // IMMEDIATE: resend the player's chunk and direct neighbors (3x3)
        // This prevents fall damage from stale hidden blocks
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX + dx, chunkZ + dz);
                if (chunk != null) {
                    PlayerChunkSender.sendChunk(player.connection, level, chunk);
                }
            }
        }

        // QUEUED: surrounding chunks beyond the 3x3 that might have boundary changes
        queueSurroundingChunks(player, state, chunkX, chunkZ, proximityRadius);
    }

    public static void onPlayerDisconnect(UUID uuid) {
        states.remove(uuid);
    }

    private static boolean isNearHiddenZone(int playerSectionY, int thresholdSection, int proximityRadius) {
        int hiddenTopBlockY = (thresholdSection << 4) - 1;
        int playerBlockY = playerSectionY << 4;
        return playerBlockY <= hiddenTopBlockY + proximityRadius;
    }

    private static void queueSurroundingChunks(ServerPlayer player, PlayerState state,
                                                int playerChunkX, int playerChunkZ,
                                                int proximityRadius) {
        RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
                ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer) player).moonrise$getChunkLoader();
        LongOpenHashSet sentChunks = loader.getSentChunksRaw();

        int outerChunkRadius = (proximityRadius >> 4) + 2;

        state.resendQueue.clear();

        for (long chunkKey : sentChunks) {
            int cx = (int) chunkKey;
            int cz = (int) (chunkKey >> 32);

            int dx = Math.abs(cx - playerChunkX);
            int dz = Math.abs(cz - playerChunkZ);

            // Skip the 3x3 we already sent immediately
            if (dx <= 1 && dz <= 1) continue;

            // Only queue chunks within range
            if (dx > outerChunkRadius || dz > outerChunkRadius) continue;

            state.resendQueue.enqueue(chunkKey);
        }
    }

    private static void drainQueue(ServerPlayer player, PlayerState state) {
        if (state.resendQueue.isEmpty()) return;

        ServerLevel level = player.level();
        int count = 0;

        while (!state.resendQueue.isEmpty() && count < QUEUED_CHUNKS_PER_TICK) {
            long chunkKey = state.resendQueue.dequeueLong();
            int cx = (int) chunkKey;
            int cz = (int) (chunkKey >> 32);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) {
                PlayerChunkSender.sendChunk(player.connection, level, chunk);
                count++;
            }
        }
    }
}
