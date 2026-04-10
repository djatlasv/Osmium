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
 * Optimizations:
 * - Tracks per-chunk "revealed" state — only resends chunks whose
 *   hidden/revealed status actually changed, skipping chunks that
 *   already show the correct data.
 * - 4-block Y hysteresis prevents jump oscillation.
 * - Drain rate of 4/tick spreads neighbor updates over 2-3 ticks.
 * - Skips chunks entirely above the threshold (nothing to hide).
 */
public class OsmiumYLevelTracker {

    private static final int QUEUED_CHUNKS_PER_TICK = 4;
    private static final int Y_HYSTERESIS = 4;

    private static final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    // Clear stale revealed state every 10 seconds to prevent chunks getting stuck
    private static final int CLEAR_INTERVAL_TICKS = 200;

    private static class PlayerState {
        int chunkX, chunkZ, blockY;
        int lastResendY;
        long lastClearTick;
        boolean initialized;
        // Tracks which chunks were last sent as "revealed" (real blocks visible)
        // If a chunk key is in this set, it was sent with proximity reveal.
        // If not, it was sent with hiding applied (or never resent by us).
        final LongOpenHashSet revealedChunks = new LongOpenHashSet();
        final LongArrayFIFOQueue resendQueue = new LongArrayFIFOQueue();
    }

    public static void onPlayerTick(ServerPlayer player) {
        if (!OsmiumConfig.chunkHidingEnabled) return;

        UUID uuid = player.getUUID();
        PlayerState state = states.computeIfAbsent(uuid, k -> new PlayerState());

        drainQueue(player, state);

        // Periodically clear revealed state so stale entries don't prevent resends
        long currentTick = player.level().getGameTime();
        if (currentTick - state.lastClearTick >= CLEAR_INTERVAL_TICKS) {
            state.lastClearTick = currentTick;
            state.revealedChunks.clear();
        }

        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;
        int blockY = player.blockPosition().getY();

        if (!state.initialized) {
            state.chunkX = chunkX;
            state.chunkZ = chunkZ;
            state.blockY = blockY;
            state.lastResendY = blockY;
            state.initialized = true;
            return;
        }

        boolean chunkChanged = chunkX != state.chunkX || chunkZ != state.chunkZ;
        boolean yMovedEnough = Math.abs(blockY - state.lastResendY) >= Y_HYSTERESIS;

        if (!chunkChanged && !yMovedEnough) {
            state.blockY = blockY;
            return;
        }

        int thresholdSection = OsmiumConfig.chunkHidingYThreshold >> 4;
        int proximityRadius = OsmiumConfig.chunkHidingProximityRadius;

        boolean wasNear = isNearHiddenZone(state.blockY >> 4, thresholdSection, proximityRadius);
        boolean isNear = isNearHiddenZone(blockY >> 4, thresholdSection, proximityRadius);

        int oldBlockY = state.blockY;
        state.chunkX = chunkX;
        state.chunkZ = chunkZ;
        state.blockY = blockY;

        if (!wasNear && !isNear) return;

        if (yMovedEnough) state.lastResendY = blockY;

        queueChangedChunks(player, state, chunkX, chunkZ, blockY, oldBlockY,
                           proximityRadius, thresholdSection, yMovedEnough);
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
     * Determine which chunks need resending by checking if their
     * revealed/hidden state changed. Only queues chunks that flipped.
     */
    private static void queueChangedChunks(ServerPlayer player, PlayerState state,
                                            int chunkX, int chunkZ, int blockY, int oldBlockY,
                                            int proximityRadius, int thresholdSection,
                                            boolean includeOuter) {
        state.resendQueue.clear();

        ServerLevel level = player.level();
        int proxSq = proximityRadius * proximityRadius;
        int playerBlockX = player.blockPosition().getX();
        int playerBlockZ = player.blockPosition().getZ();
        // Check Y at the top of the hidden zone (the boundary that matters)
        int checkY = (thresholdSection << 4) - 8;

        // Determine range of chunks to check
        int outerChunkRadius = includeOuter ? (proximityRadius >> 4) + 2 : 1;
        boolean sentOwn = false;

        RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
                ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer) player).moonrise$getChunkLoader();
        LongOpenHashSet sentChunks = loader.getSentChunksRaw();

        for (long chunkKey : sentChunks) {
            int cx = (int) chunkKey;
            int cz = (int) (chunkKey >> 32);

            int dx = Math.abs(cx - chunkX);
            int dz = Math.abs(cz - chunkZ);
            if (dx > outerChunkRadius || dz > outerChunkRadius) continue;

            // Skip chunks entirely above the threshold — nothing to hide
            // (min section Y for overworld is -4, threshold section 0 means sections -4 to -1 are hidden)
            // We can't easily check per-chunk, so use a simple heuristic:
            // the chunk must be loaded and sent to us — that's guaranteed by sentChunks

            // Compute distance from player to nearest block in this chunk at the check Y
            int chunkMinX = cx << 4;
            int chunkMinZ = cz << 4;
            int nearX = Math.max(chunkMinX, Math.min(playerBlockX, chunkMinX + 15));
            int nearZ = Math.max(chunkMinZ, Math.min(playerBlockZ, chunkMinZ + 15));
            int dyCheck = blockY - checkY;
            int distSq = (playerBlockX - nearX) * (playerBlockX - nearX)
                       + dyCheck * dyCheck
                       + (playerBlockZ - nearZ) * (playerBlockZ - nearZ);

            boolean shouldBeRevealed = distSq <= proxSq;
            boolean wasRevealed = state.revealedChunks.contains(chunkKey);

            if (shouldBeRevealed == wasRevealed) continue; // no change, skip

            // Update state
            if (shouldBeRevealed) {
                state.revealedChunks.add(chunkKey);
            } else {
                state.revealedChunks.remove(chunkKey);
            }

            // Player's own chunk gets sent immediately
            if (cx == chunkX && cz == chunkZ) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) {
                    PlayerChunkSender.sendChunk(player.connection, level, chunk);
                }
                sentOwn = true;
                continue;
            }

            state.resendQueue.enqueue(chunkKey);
        }

        // Always resend own chunk if it wasn't in the changed set but we moved
        if (!sentOwn) {
            LevelChunk ownChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (ownChunk != null) {
                // Check if it actually has hidden sections worth resending
                if (blockY < (thresholdSection << 4) + proximityRadius) {
                    boolean shouldBeRevealed = true; // own chunk is always within proximity
                    long ownKey = ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
                    if (!state.revealedChunks.contains(ownKey)) {
                        state.revealedChunks.add(ownKey);
                        PlayerChunkSender.sendChunk(player.connection, level, ownChunk);
                    }
                }
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
