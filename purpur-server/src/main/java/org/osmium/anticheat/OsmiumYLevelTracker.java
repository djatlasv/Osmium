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
 * Resends only chunks whose hidden/revealed status actually changed when
 * the player moves. Instead of blasting the 3x3 or all nearby chunks,
 * computes which chunks crossed the proximity boundary and resends only those.
 */
public class OsmiumYLevelTracker {

    private static final int QUEUED_CHUNKS_PER_TICK = 10;

    private static final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    private static class PlayerState {
        int blockX, blockY, blockZ;
        boolean initialized;
        final LongArrayFIFOQueue resendQueue = new LongArrayFIFOQueue();
    }

    public static void onPlayerTick(ServerPlayer player) {
        if (!OsmiumConfig.chunkHidingEnabled) return;

        UUID uuid = player.getUUID();
        PlayerState state = states.computeIfAbsent(uuid, k -> new PlayerState());

        drainQueue(player, state);

        int blockX = player.blockPosition().getX();
        int blockY = player.blockPosition().getY();
        int blockZ = player.blockPosition().getZ();

        if (!state.initialized) {
            state.blockX = blockX;
            state.blockY = blockY;
            state.blockZ = blockZ;
            state.initialized = true;
            return;
        }

        // Only act when the player enters a new chunk or section
        boolean chunkChanged = (blockX >> 4) != (state.blockX >> 4) || (blockZ >> 4) != (state.blockZ >> 4);
        boolean sectionYChanged = (blockY >> 4) != (state.blockY >> 4);

        if (!chunkChanged && !sectionYChanged) {
            state.blockX = blockX;
            state.blockY = blockY;
            state.blockZ = blockZ;
            return;
        }

        int thresholdSection = OsmiumConfig.chunkHidingYThreshold >> 4;
        int proximityRadius = OsmiumConfig.chunkHidingProximityRadius;

        boolean wasNear = isNearHiddenZone(state.blockY >> 4, thresholdSection, proximityRadius);
        boolean isNear = isNearHiddenZone(blockY >> 4, thresholdSection, proximityRadius);

        int oldX = state.blockX;
        int oldY = state.blockY;
        int oldZ = state.blockZ;

        state.blockX = blockX;
        state.blockY = blockY;
        state.blockZ = blockZ;

        if (!wasNear && !isNear) return;

        // Find chunks whose proximity status changed and queue them
        queueChangedChunks(player, state, oldX, oldY, oldZ, blockX, blockY, blockZ, proximityRadius);
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
     * For each sent chunk, check if any section below the threshold crossed
     * the proximity boundary (was hidden, now revealed, or vice versa).
     * Only queue chunks where the status actually changed.
     */
    private static void queueChangedChunks(ServerPlayer player, PlayerState state,
                                            int oldX, int oldY, int oldZ,
                                            int newX, int newY, int newZ,
                                            int proximityRadius) {
        RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
                ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer) player).moonrise$getChunkLoader();
        LongOpenHashSet sentChunks = loader.getSentChunksRaw();

        int proxSq = proximityRadius * proximityRadius;
        int thresholdY = OsmiumConfig.chunkHidingYThreshold;
        // Check at the top of the hidden zone — the boundary that matters most
        int checkY = thresholdY - 8; // middle of the top hidden section

        // Only consider chunks within possible range of the sphere
        int outerChunkRadius = (proximityRadius >> 4) + 2;
        int newChunkX = newX >> 4;
        int newChunkZ = newZ >> 4;
        int oldChunkX = oldX >> 4;
        int oldChunkZ = oldZ >> 4;

        state.resendQueue.clear();

        for (long chunkKey : sentChunks) {
            int cx = (int) chunkKey;
            int cz = (int) (chunkKey >> 32);

            // Quick range filter — skip chunks far from both positions
            int dxNew = Math.abs(cx - newChunkX);
            int dzNew = Math.abs(cz - newChunkZ);
            int dxOld = Math.abs(cx - oldChunkX);
            int dzOld = Math.abs(cz - oldChunkZ);

            if ((dxNew > outerChunkRadius || dzNew > outerChunkRadius)
             && (dxOld > outerChunkRadius || dzOld > outerChunkRadius)) continue;

            // Compute XZ distance to nearest block in chunk from old and new positions
            int chunkMinX = cx << 4;
            int chunkMinZ = cz << 4;

            int nearXold = Math.max(chunkMinX, Math.min(oldX, chunkMinX + 15));
            int nearZold = Math.max(chunkMinZ, Math.min(oldZ, chunkMinZ + 15));
            int nearXnew = Math.max(chunkMinX, Math.min(newX, chunkMinX + 15));
            int nearZnew = Math.max(chunkMinZ, Math.min(newZ, chunkMinZ + 15));

            // 3D distance to the check point (top of hidden zone)
            int dyOld = oldY - checkY;
            int dyNew = newY - checkY;

            int distSqOld = (oldX - nearXold) * (oldX - nearXold) + dyOld * dyOld + (oldZ - nearZold) * (oldZ - nearZold);
            int distSqNew = (newX - nearXnew) * (newX - nearXnew) + dyNew * dyNew + (newZ - nearZnew) * (newZ - nearZnew);

            boolean wasRevealed = distSqOld <= proxSq;
            boolean nowRevealed = distSqNew <= proxSq;

            // Only resend if status changed
            if (wasRevealed != nowRevealed) {
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
