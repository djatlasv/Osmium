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
 * Resends chunks when the player moves near the hidden zone.
 *
 * - Any chunk/section change while near zone: immediate 3x3 resend
 * - Section Y change: also queues surrounding chunks at the sphere boundary
 * - Optimized: skips resend if the player's chunk didn't change
 */
public class OsmiumYLevelTracker {

    private static final int QUEUED_CHUNKS_PER_TICK = 10;

    private static final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    private static class PlayerState {
        int chunkX, chunkZ, sectionY;
        boolean initialized;
        final LongArrayFIFOQueue resendQueue = new LongArrayFIFOQueue();
    }

    public static void onPlayerTick(ServerPlayer player) {
        if (!OsmiumConfig.chunkHidingEnabled) return;

        UUID uuid = player.getUUID();
        PlayerState state = states.computeIfAbsent(uuid, k -> new PlayerState());

        drainQueue(player, state);

        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;
        int sectionY = player.blockPosition().getY() >> 4;

        if (!state.initialized) {
            state.chunkX = chunkX;
            state.chunkZ = chunkZ;
            state.sectionY = sectionY;
            state.initialized = true;
            return;
        }

        boolean chunkChanged = chunkX != state.chunkX || chunkZ != state.chunkZ;
        boolean sectionYChanged = sectionY != state.sectionY;

        if (!chunkChanged && !sectionYChanged) return;

        int thresholdSection = OsmiumConfig.chunkHidingYThreshold >> 4;
        int proximityRadius = OsmiumConfig.chunkHidingProximityRadius;

        boolean wasNear = isNearHiddenZone(state.sectionY, thresholdSection, proximityRadius);
        boolean isNear = isNearHiddenZone(sectionY, thresholdSection, proximityRadius);

        state.chunkX = chunkX;
        state.chunkZ = chunkZ;
        state.sectionY = sectionY;

        if (!wasNear && !isNear) return;

        ServerLevel level = player.level();

        // Immediate: resend ONLY the player's own chunk (1 packet, no jitter)
        LevelChunk ownChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (ownChunk != null) {
            PlayerChunkSender.sendChunk(player.connection, level, ownChunk);
        }

        // Queue the 8 neighbors + boundary chunks — drained smoothly across ticks
        queueNearbyChunks(player, state, chunkX, chunkZ, proximityRadius, sectionYChanged);
    }

    public static void onPlayerDisconnect(UUID uuid) {
        states.remove(uuid);
    }

    private static boolean isNearHiddenZone(int playerSectionY, int thresholdSection, int proximityRadius) {
        int hiddenTopBlockY = (thresholdSection << 4) - 1;
        int playerBlockY = playerSectionY << 4;
        return playerBlockY <= hiddenTopBlockY + proximityRadius;
    }

    private static void queueNearbyChunks(ServerPlayer player, PlayerState state,
                                          int playerChunkX, int playerChunkZ,
                                          int proximityRadius, boolean includeOuter) {
        state.resendQueue.clear();

        // Always queue the 8 neighbors (player's own chunk sent immediately above)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                state.resendQueue.enqueue(
                    ((long)(playerChunkX + dx) & 0xFFFFFFFFL) | (((long)(playerChunkZ + dz) & 0xFFFFFFFFL) << 32));
            }
        }

        // On Y changes, also queue boundary chunks beyond the 3x3
        if (includeOuter) {
            RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
                    ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer) player).moonrise$getChunkLoader();
            LongOpenHashSet sentChunks = loader.getSentChunksRaw();

            int outerChunkRadius = (proximityRadius >> 4) + 2;

            for (long chunkKey : sentChunks) {
                int cx = (int) chunkKey;
                int cz = (int) (chunkKey >> 32);

                int dx = Math.abs(cx - playerChunkX);
                int dz = Math.abs(cz - playerChunkZ);

                if (dx <= 1 && dz <= 1) continue; // already queued above
                if (dx > outerChunkRadius || dz > outerChunkRadius) continue;

                state.resendQueue.enqueue(chunkKey);
            }
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
