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
 * Tracks player positions and resends chunks when they move near the
 * hidden zone so that the proximity-based reveal/re-hide stays in sync.
 *
 * Resends when:
 * - Player crosses the near/far Y boundary (entering or leaving the zone)
 * - Player moves to a different chunk while near the zone (XZ movement
 *   changes which sections are revealed)
 */
public class OsmiumYLevelTracker {

    // Pack chunkX, chunkZ, sectionY into a long for cheap comparison
    private static final Map<UUID, Long> lastPosition = new ConcurrentHashMap<>();

    public static void onPlayerTick(ServerPlayer player) {
        if (!OsmiumConfig.chunkHidingEnabled) return;

        UUID uuid = player.getUUID();
        int chunkX = player.blockPosition().getX() >> 4;
        int chunkZ = player.blockPosition().getZ() >> 4;
        int sectionY = player.blockPosition().getY() >> 4;
        long packed = pack(chunkX, chunkZ, sectionY);

        Long previous = lastPosition.put(uuid, packed);
        if (previous == null || previous == packed) return;

        int prevChunkX = unpackX(previous);
        int prevChunkZ = unpackZ(previous);
        int prevSectionY = unpackY(previous);

        int thresholdSection = OsmiumConfig.chunkHidingYThreshold >> 4;
        int proximityRadius = OsmiumConfig.chunkHidingProximityRadius;

        boolean wasNear = isNearHiddenZone(prevSectionY, thresholdSection, proximityRadius);
        boolean isNear = isNearHiddenZone(sectionY, thresholdSection, proximityRadius);

        boolean shouldResend = false;

        // Crossed the near/far boundary
        if (wasNear != isNear) {
            shouldResend = true;
        }
        // Moved to a different chunk while near the zone — proximity sphere shifted
        else if (isNear && (chunkX != prevChunkX || chunkZ != prevChunkZ || sectionY != prevSectionY)) {
            shouldResend = true;
        }

        if (shouldResend) {
            resendChunks(player);
        }
    }

    public static void onPlayerDisconnect(UUID uuid) {
        lastPosition.remove(uuid);
    }

    private static boolean isNearHiddenZone(int playerSectionY, int thresholdSection, int proximityRadius) {
        // Player is "near" if their Y is within proximityRadius blocks of the top of the hidden zone
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

    // Packing: X in bits 0-20, Z in bits 21-41, Y in bits 42-52
    private static long pack(int x, int z, int y) {
        return ((long) x & 0x1FFFFFL) | (((long) z & 0x1FFFFFL) << 21) | (((long) y & 0x7FFL) << 42);
    }

    private static int unpackX(long packed) {
        int raw = (int) (packed & 0x1FFFFFL);
        return (raw << 11) >> 11; // sign-extend from 21 bits
    }

    private static int unpackZ(long packed) {
        int raw = (int) ((packed >> 21) & 0x1FFFFFL);
        return (raw << 11) >> 11;
    }

    private static int unpackY(long packed) {
        int raw = (int) ((packed >> 42) & 0x7FFL);
        return (raw << 21) >> 21; // sign-extend from 11 bits
    }
}
