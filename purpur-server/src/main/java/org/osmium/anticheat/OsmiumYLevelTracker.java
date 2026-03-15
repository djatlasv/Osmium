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
 * Tracks player positions and queues chunk resends when they move near the
 * hidden zone. Rate-limited: processes a fixed number of chunks per tick
 * to avoid flooding the client with packets.
 */
public class OsmiumYLevelTracker {

    // Max chunks to resend per player per tick
    private static final int CHUNKS_PER_TICK = 16;
    // Min ticks between queueing new resend batches
    private static final int RESEND_COOLDOWN = 5;

    private static final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    private static class PlayerState {
        int chunkX, chunkZ, sectionY;
        long lastResendTick;
        final LongArrayFIFOQueue resendQueue = new LongArrayFIFOQueue();
    }

    public static void onPlayerTick(ServerPlayer player) {
        if (!OsmiumConfig.chunkHidingEnabled) return;

        UUID uuid = player.getUUID();
        PlayerState state = states.computeIfAbsent(uuid, k -> new PlayerState());

        // Always drain the queue first — spread resends across ticks
        drainQueue(player, state);

        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;
        int sectionY = player.blockPosition().getY() >> 4;

        // First tick — just record position
        if (state.lastResendTick == 0) {
            state.chunkX = chunkX;
            state.chunkZ = chunkZ;
            state.sectionY = sectionY;
            state.lastResendTick = player.level().getGameTime();
            return;
        }

        // No movement at chunk/section level
        if (chunkX == state.chunkX && chunkZ == state.chunkZ && sectionY == state.sectionY) {
            return;
        }

        int thresholdSection = OsmiumConfig.chunkHidingYThreshold >> 4;
        int proximityRadius = OsmiumConfig.chunkHidingProximityRadius;
        long currentTick = player.level().getGameTime();

        boolean wasNear = isNearHiddenZone(state.sectionY, thresholdSection, proximityRadius);
        boolean isNear = isNearHiddenZone(sectionY, thresholdSection, proximityRadius);

        boolean shouldQueue = false;

        if (wasNear != isNear) {
            // Crossed the boundary
            shouldQueue = true;
        } else if (isNear) {
            // Moved while near the zone
            shouldQueue = true;
        }

        int oldChunkX = state.chunkX;
        int oldChunkZ = state.chunkZ;

        state.chunkX = chunkX;
        state.chunkZ = chunkZ;
        state.sectionY = sectionY;

        if (shouldQueue && currentTick - state.lastResendTick >= RESEND_COOLDOWN) {
            state.lastResendTick = currentTick;
            queueBoundaryChunks(player, state, oldChunkX, oldChunkZ, chunkX, chunkZ, proximityRadius);
        }
    }

    public static void onPlayerDisconnect(UUID uuid) {
        states.remove(uuid);
    }

    private static boolean isNearHiddenZone(int playerSectionY, int thresholdSection, int proximityRadius) {
        int hiddenTopBlockY = (thresholdSection << 4) - 1;
        int playerBlockY = playerSectionY << 4;
        return playerBlockY <= hiddenTopBlockY + proximityRadius;
    }

    /**
     * Queue only chunks near the boundary of the proximity sphere — the ones
     * whose hidden/revealed status could have changed when the player moved.
     * Chunks deep inside (always revealed) or far outside (always hidden)
     * don't need resending.
     */
    private static void queueBoundaryChunks(ServerPlayer player, PlayerState state,
                                             int oldChunkX, int oldChunkZ,
                                             int newChunkX, int newChunkZ,
                                             int proximityRadius) {
        RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
                ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer) player).moonrise$getChunkLoader();
        LongOpenHashSet sentChunks = loader.getSentChunksRaw();

        // Chunks within this distance could have sections at the sphere boundary
        int outerChunkRadius = (proximityRadius >> 4) + 2;
        // Chunks closer than this are fully inside the sphere for all relevant Y levels
        int innerChunkRadius = Math.max(0, (proximityRadius >> 4) - 3);

        state.resendQueue.clear();

        for (long chunkKey : sentChunks) {
            int cx = (int) chunkKey;
            int cz = (int) (chunkKey >> 32);

            // Check against both old and new position — transitioning chunks are near either
            int dxOld = Math.abs(cx - oldChunkX);
            int dzOld = Math.abs(cz - oldChunkZ);
            int dxNew = Math.abs(cx - newChunkX);
            int dzNew = Math.abs(cz - newChunkZ);

            boolean nearOld = dxOld <= outerChunkRadius && dzOld <= outerChunkRadius;
            boolean nearNew = dxNew <= outerChunkRadius && dzNew <= outerChunkRadius;

            if (!nearOld && !nearNew) continue; // too far from both positions

            // Skip chunks deep inside the sphere (always revealed, nothing changes)
            boolean deepInsideOld = dxOld <= innerChunkRadius && dzOld <= innerChunkRadius;
            boolean deepInsideNew = dxNew <= innerChunkRadius && dzNew <= innerChunkRadius;
            if (deepInsideOld && deepInsideNew) continue;

            state.resendQueue.enqueue(chunkKey);
        }
    }

    /**
     * Send up to CHUNKS_PER_TICK chunks from the player's resend queue.
     */
    private static void drainQueue(ServerPlayer player, PlayerState state) {
        if (state.resendQueue.isEmpty()) return;

        ServerLevel level = player.level();
        int count = 0;

        while (!state.resendQueue.isEmpty() && count < CHUNKS_PER_TICK) {
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
