package org.osmium.anticheat;

import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.Level;
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

    // Full chunk packets are ~MB each: 4/tick saturated player links
    // (observed 75-92 chunk packets/s -> 3000ms keepalive ping). 2/tick
    // halves the worst-case burst while keeping reveal latency acceptable.
    private static final int QUEUED_CHUNKS_PER_TICK = 2;
    private static final int Y_HYSTERESIS = 4;
    // A revealed chunk only flips back to hidden after the player moved
    // this many blocks BEYOND the proximity radius — walking along the
    // boundary no longer oscillates a column of chunks hidden/revealed.
    private static final int FLIP_HYSTERESIS = 4;

    private static final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();


    private static class PlayerState {
        int chunkX, chunkZ, blockY;
        int lastResendY;
        boolean initialized;
        // Set when the player moved so far that per-chunk state is unreliable
        // (teleport/respawn) — triggers a full re-scan of all sent chunks
        boolean needsFullScan;
        ResourceKey<Level> dimension;
        // Tracks which chunks were last sent as "revealed" (real blocks visible)
        // If a chunk key is in this set, it was sent with proximity reveal.
        // If not, it was sent with hiding applied (or never resent by us).
        final LongOpenHashSet revealedChunks = new LongOpenHashSet();
        final LongArrayFIFOQueue resendQueue = new LongArrayFIFOQueue();
        // Dedup for queued resends: the queue must NOT be cleared on
        // movement ticks — a dropped entry leaks real blocks (its flip state
        // was already committed). Only teleports (fullScan) clear it.
        final LongOpenHashSet resendPending = new LongOpenHashSet();
    }

    public static void onPlayerTick(ServerPlayer player) {
        if (!OsmiumConfig.chunkHidingEnabled) return;

        UUID uuid = player.getUUID();
        PlayerState state = states.computeIfAbsent(uuid, k -> new PlayerState());

        drainQueue(player, state);

        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;
        int blockY = player.blockPosition().getY();

        // Dimension switch invalidates all tracked chunk keys
        ResourceKey<Level> dim = player.level().dimension();
        if (state.dimension != null && state.dimension != dim) {
            state.needsFullScan = true;
            state.revealedChunks.clear();
            state.resendQueue.clear();
            state.resendPending.clear();
            state.lastResendY = blockY;
            state.chunkX = chunkX;
            state.chunkZ = chunkZ;
            state.blockY = blockY;
            state.dimension = dim;
            return;
        }
        state.dimension = dim;

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

        // Detect teleports/large jumps: if the player moved beyond the tracked
        // radius, per-chunk revealed state can no longer be diffed reliably.
        // A full re-scan of all sent chunks fixes both directions (chunks that
        // must now be hidden AND chunks stuck showing real blocks).
        int jumpChunks = (OsmiumConfig.chunkHidingProximityRadius >> 4) + 2;
        boolean bigJump = Math.abs(chunkX - state.chunkX) > jumpChunks
                       || Math.abs(chunkZ - state.chunkZ) > jumpChunks;
        if (bigJump) state.needsFullScan = true;

        int thresholdSection = OsmiumConfig.chunkHidingYThreshold >> 4;
        int proximityRadius = OsmiumConfig.chunkHidingProximityRadius;

        boolean wasNear = isNearHiddenZone(state.blockY >> 4, thresholdSection, proximityRadius);
        boolean isNear = isNearHiddenZone(blockY >> 4, thresholdSection, proximityRadius);

        int oldBlockY = state.blockY;
        state.chunkX = chunkX;
        state.chunkZ = chunkZ;
        state.blockY = blockY;

        boolean fullScan = state.needsFullScan;
        if (!wasNear && !isNear && !fullScan) return;

        state.needsFullScan = false;

        if (yMovedEnough) state.lastResendY = blockY;

        queueChangedChunks(player, state, chunkX, chunkZ, blockY, oldBlockY,
                           proximityRadius, thresholdSection,
                           yMovedEnough || fullScan, fullScan);
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
                                            boolean includeOuter, boolean fullScan) {
        // Only teleports invalidate queued resends; normal movement ticks
        // must keep pending entries (clearing them leaked real blocks — the
        // flip state was already committed but the resend never happened).
        if (fullScan) {
            state.resendQueue.clear();
            state.resendPending.clear();
        }

        ServerLevel level = player.level();
        int proxSq = proximityRadius * proximityRadius;
        int hystSq = (proximityRadius + FLIP_HYSTERESIS)
                   * (proximityRadius + FLIP_HYSTERESIS);
        int playerBlockX = player.blockPosition().getX();
        int playerBlockZ = player.blockPosition().getZ();

        // Determine range of chunks to check
        int outerChunkRadius = includeOuter ? (proximityRadius >> 4) + 2 : 1;
        boolean sentOwn = false;

        RegionizedPlayerChunkLoader.PlayerChunkLoaderData loader =
                ((ca.spottedleaf.moonrise.patches.chunk_system.player.ChunkSystemServerPlayer) player).moonrise$getChunkLoader();
        LongOpenHashSet sentChunks = loader.getSentChunksRaw();

        for (long chunkKey : sentChunks) {
            int cx = (int) chunkKey;
            int cz = (int) (chunkKey >> 32);

            if (!fullScan) {
                int dx = Math.abs(cx - chunkX);
                int dz = Math.abs(cz - chunkZ);
                if (dx > outerChunkRadius || dz > outerChunkRadius) continue;
            }

            // Skip chunks entirely above the threshold — nothing to hide
            // (min section Y for overworld is -4, threshold section 0 means sections -4 to -1 are hidden)
            // We can't easily check per-chunk, so use a simple heuristic:
            // the chunk must be loaded and sent to us — that's guaranteed by sentChunks

            // Horizontal distance to the chunk — reveal is XZ-based (see processor)
            int chunkMinX = cx << 4;
            int chunkMinZ = cz << 4;
            int nearX = Math.max(chunkMinX, Math.min(playerBlockX, chunkMinX + 15));
            int nearZ = Math.max(chunkMinZ, Math.min(playerBlockZ, chunkMinZ + 15));
            int distSq = (playerBlockX - nearX) * (playerBlockX - nearX)
                       + (playerBlockZ - nearZ) * (playerBlockZ - nearZ);

            // Flip with hysteresis: reveal immediately when entering the radius,
            // but only re-hide once beyond radius + FLIP_HYSTERESIS.
            boolean wasRevealed = state.revealedChunks.contains(chunkKey);
            boolean shouldBeRevealed = distSq <= proxSq
                    || (wasRevealed && distSq <= hystSq);

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

            // Dedup: a chunk may flip back while its flip-send is still pending;
            // the drain ships the CURRENT state at send time, so one entry
            // always suffices.
            if (state.resendPending.add(chunkKey)) {
                state.resendQueue.enqueue(chunkKey);
            }
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
            state.resendPending.remove(chunkKey);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) {
                PlayerChunkSender.sendChunk(player.connection, level, chunk);
                count++;
            }
        }
    }
}
