package org.osmium.anticheat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.osmium.OsmiumConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Raycast-based occlusion engine, RayTraceAntiXray model (native adaptation
 * of github.com/stonar96/RayTraceAntiXray, MIT — attributed). See design
 * notes at the top of each section.
 *
 * BLOCK occlusion (raytrace antixray, per-player candidate model):
 *   Chunk packets hide ALL target blocks in RTAX-owned sections (near or
 *   above-threshold — see OsmiumChunkProcessor section ownership) and
 *   register the air-exposed ones as per-player candidates. A worker pool
 *   traces candidates every tick: frustum cull, distance cull, then a
 *   crack-detecting Amanatides–Woo walk (ported BlockOcclusionCulling).
 *   Visible candidates are revealed with per-block update packets — no full
 *   chunk resends. Revealed candidates are dropped (RTAX rehide-blocks:
 *   false); they return hidden on the next chunk packet. Unloaded chunks
 *   fail CLOSED in traces (a ray through unloaded terrain cannot confirm
 *   LOS); packet-side lookups fail open.
 *
 * ENTITY occlusion:
 *   Main-thread vanilla clip() from player eyes to the entity bounding box,
 *   with a verdict TTL cache so each entity+player pair traces at most once
 *   per check interval. Players are never occluded.
 *
 * Everything fails open (visible): a stale "visible" verdict costs nothing,
 * a wrongly hidden one is corrected on the next trace — never a desync
 * risk for legitimate players.
 */
public final class OsmiumOcclusion {

    private OsmiumOcclusion() {}

    // ------------------------------------------------------------------
    // Block occlusion state — per-player candidate model (RTAX)
    // ------------------------------------------------------------------

    /**
     * RTAX max-ray-trace-block-count-per-chunk (shipped default): cap on
     * candidates registered per chunk packet. Air-exposure filtering keeps
     * real counts far below this in natural terrain.
     */
    static final int MAX_CANDIDATES_PER_CHUNK = 100;

    /** Per-player trace state. Keyed by player UUID. */
    private static final ConcurrentHashMap<UUID, PlayerData> playerData = new ConcurrentHashMap<>();

    /** Players with a trace task currently queued/running (per-tick dedup). */
    private static final Set<UUID> tracing = ConcurrentHashMap.newKeySet();

    /** Candidates for one chunk, as shipped to ONE player. */
    private static final class ChunkCandidates {
        final ServerLevel level;
        final long chunkKey;
        final int chunkX;
        final int chunkZ;
        /** candidate position -> hidden state (true = client is shown fake block) */
        final ConcurrentHashMap<BlockPos, Boolean> blocks = new ConcurrentHashMap<>();

        ChunkCandidates(ServerLevel level, long chunkKey, int chunkX, int chunkZ) {
            this.level = level;
            this.chunkKey = chunkKey;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }

    private static final class PlayerData {
        final ServerPlayer player;
        final ConcurrentHashMap<Long, ChunkCandidates> chunks = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();

        PlayerData(ServerPlayer player) {
            this.player = player;
        }
    }

    /** A pending reveal: send the live block state at pos to the player. */
    private record Result(ChunkCandidates chunk, BlockPos pos) {}

    /**
     * Packet-side registration (called from OsmiumChunkProcessor while the
     * chunk packet is built — any thread). A fresh chunk packet replaces the
     * player's previous candidate set for that chunk; pending reveal results
     * for the old set are discarded by the freshness check at drain time.
     */
    public static void registerCandidates(ServerPlayer player, LevelChunk chunk, List<BlockPos> candidates) {
        if (!OsmiumConfig.raytraceHidingEnabled) return;
        UUID id = player.getUUID();
        PlayerData pd = playerData.get(id);
        if (pd == null || pd.player != player) {
            pd = new PlayerData(player);
            playerData.put(id, pd);
        }
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        long key = chunkKey(cx, cz);
        ChunkCandidates cc = new ChunkCandidates((ServerLevel) chunk.getLevel(), key, cx, cz);
        for (BlockPos pos : candidates) {
            cc.blocks.put(pos, Boolean.TRUE);
        }
        ChunkCandidates old = pd.chunks.put(key, cc);
        if (OsmiumConfig.raytraceDebug && old != null && !old.blocks.isEmpty()) {
            debugLog("[antixray] re-registered chunk " + cx + "," + cz
                    + " (old=" + old.blocks.size() + " new=" + cc.blocks.size() + ")");
        }
    }

    // ------------------------------------------------------------------
    // Block occlusion — hooks
    // ------------------------------------------------------------------

    private static void debugLog(String msg) {
        if (OsmiumConfig.raytraceDebug) LOGGER.info(msg);
    }

    /** Public entry for debug logs from the chunk processor. */
    public static void debugLogPublic(String msg) {
        debugLog(msg);
    }

    /**
     * Block change hook (main thread): RTAX updateNearbyBlocks port. When a
     * solid block is removed, TARGET blocks within manhattan radius 2 are
     * re-sent with their REAL state via vanilla blockChanged broadcasts —
     * buried targets are never candidates, so mining next to one is the only
     * thing that can reveal it. Also drops any per-player candidate entry
     * for the re-sent positions.
     */
    public static void onBlockChanged(Level level, BlockPos pos,
                                      BlockState newBlockState, BlockState oldBlockState) {
        if (!OsmiumConfig.raytraceHidingEnabled || !(level instanceof ServerLevel sl)) return;
        if (oldBlockState == null || newBlockState == null) return;
        if (!oldBlockState.isSolidRender() || newBlockState.isSolidRender()) return;

        Set<Block> targets = targets();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (manhattan > 2) continue; // RTAX updateRadius=2 pattern
                    probe.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    BlockState state = level.getBlockStateIfLoaded(probe);
                    if (state == null || !targets.contains(state.getBlock())) continue;

                    // Real-state broadcast to tracking players (vanilla path).
                    sl.getChunkSource().blockChanged(probe.immutable());

                    // Drop stale candidates: the client now holds the truth.
                    long ck = chunkKey(probe.getX() >> 4, probe.getZ() >> 4);
                    for (PlayerData pd : playerData.values()) {
                        if (pd.player.level() != level) continue;
                        ChunkCandidates cc = pd.chunks.get(ck);
                        if (cc != null) cc.blocks.remove(probe);
                    }
                }
            }
        }
    }

    /** Config reload hook: drop all state. */
    public static void clearCaches() {
        resolvedTargets = null;
        playerData.clear();
        tracing.clear();
        pendingTraces.clear();
        entityVerdicts.clear();
        OsmiumChunkProcessor.clearPaletteCache(); // palette classif + shared buffers were built with old targets
    }

    // ------------------------------------------------------------------
    // Block occlusion — tick driver (main thread)
    // ------------------------------------------------------------------

    private static long lastVerdictSweep = 0;

    public static void tick(MinecraftServer server) {
        // Cheap per-tick guard: reclaim the controller seat if a plugin
        // (RayTraceAntiXray) stole it via final-field mutation.
        OsmiumChunkProcessor.ensureWrapped(server);
        OsmiumChunkProcessor.sweepEm1Pending(); // Osmium - TTL-drop EM1 packets that never flushed
        // Latency warnings piggyback this per-tick driver (patch-hook
        // cleanup is a follow-up); runs before the feature gates below.
        OsmiumLatencyWarn.tick(server);
        boolean blocks = OsmiumConfig.raytraceHidingEnabled && !targets().isEmpty();
        boolean entities = OsmiumConfig.entityOcclusionEnabled;
        if (!blocks && !entities) return;

        if (blocks) {
            drainResults();
            dispatchTraces(server);
            prunePeriodically(server);
        }

        if (entities && server.getTickCount() - lastVerdictSweep >= 20) {
            lastVerdictSweep = server.getTickCount();
            sweepEntityVerdicts(server.getTickCount());
        }
    }

    /**
     * Submits one trace task per player (RTAX: one RayTraceCallable per
     * player per tick). Per-player in-flight guard prevents pileup when a
     * trace overruns a tick.
     */
    private static void dispatchTraces(MinecraftServer server) {
        Iterator<ConcurrentHashMap.Entry<UUID, PlayerData>> it = playerData.entrySet().iterator();
        while (it.hasNext()) {
            PlayerData pd = it.next().getValue();
            ServerPlayer p = pd.player;
            if (p.connection == null || !p.connection.isAcceptingMessages()) {
                it.remove();
                continue;
            }
            // Snapshot eye + view direction on the main thread (RTAX
            // snapshots locations on PlayerMoveEvent; we sample per tick).
            Vec3 eye = p.getEyePosition();
            Vec3 look = p.getLookAngle();
            ServerLevel level = (ServerLevel) p.level();
            UUID id = p.getUUID();
            if (!tracing.add(id)) continue; // previous trace still queued/running
            double eyeX = eye.x, eyeY = eye.y, eyeZ = eye.z;
            double lookX = look.x, lookY = look.y, lookZ = look.z;
            workers().execute(() -> {
                try {
                    tracePlayer(pd, level, eyeX, eyeY, eyeZ, lookX, lookY, lookZ);
                } catch (Exception e) {
                    LOGGER.error("[Osmium] occlusion trace failed: " + e.getMessage());
                } finally {
                    tracing.remove(id);
                }
            });
        }
    }

    /** Sends pending reveal/hide-confirm updates. Main thread only. */
    private static void drainResults() {
        for (PlayerData pd : playerData.values()) {
            Result result;
            int budget = 512;
            while (budget-- > 0 && (result = pd.results.poll()) != null) {
                ChunkCandidates cc = result.chunk();
                // Freshness: a resent chunk packet replaced this candidate
                // set — stale reveals would fight the fresh packet.
                if (pd.chunks.get(cc.chunkKey) != cc) {
                    if (OsmiumConfig.raytraceDebug) debugLog("[antixray] reveal DROPPED (stale candidate set) "
                            + result.pos().toShortString());
                    continue;
                }
                if (cc.level.getChunkSource().getChunkNow(cc.chunkX, cc.chunkZ) == null) {
                    if (OsmiumConfig.raytraceDebug) debugLog("[antixray] reveal DROPPED (chunk unloaded) "
                            + result.pos().toShortString());
                    continue;
                }
                BlockState real = cc.level.getBlockState(result.pos());
                if (OsmiumConfig.raytraceDebug) {
                    debugLog("[antixray] revealed " + result.pos().toShortString() + " -> "
                            + BuiltInRegistries.BLOCK.getKey(real.getBlock()) + " for "
                            + pd.player.getGameProfile().name());
                }
                pd.player.connection.send(new ClientboundBlockUpdatePacket(result.pos(), real));
                if (real.hasBlockEntity()) {
                    var be = cc.level.getBlockEntity(result.pos());
                    if (be != null) {
                        pd.player.connection.send(ClientboundBlockEntityDataPacket.create(be));
                    }
                }
            }
        }
    }

    /** Slow cleanup: candidates for chunks the player no longer tracks.
     *  Distance-based: 26.3's chunk system no longer populates
     *  ServerPlayer.chunkTrackingView (only ever set to EMPTY), so the old
     *  tracking-view check pruned EVERY set 1s after registration — targets
     *  stayed hidden until the next chunk resend. Candidate sets are also
     *  replaced wholesale on resend, so this only cleans up moved-away
     *  chunks that stopped being resent. */
    private static void prunePeriodically(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        int maxDist = server.getPlayerList().getViewDistance() + 2;
        long maxDistSq = (long) maxDist * maxDist;
        for (PlayerData pd : playerData.values()) {
            ServerPlayer p = pd.player;
            int px = p.blockPosition().getX() >> 4;
            int pz = p.blockPosition().getZ() >> 4;
            pd.chunks.entrySet().removeIf(e -> {
                ChunkCandidates cc = e.getValue();
                // Empty sets stay while reveal results are still pending:
                // drainResults' freshness check requires the map entry to
                // survive until those results are processed.
                if (cc.blocks.isEmpty() && !pd.results.isEmpty()) return false;
                if (cc.blocks.isEmpty()) return true;
                int dx = cc.chunkX - px;
                int dz = cc.chunkZ - pz;
                return (long) dx * dx + (long) dz * dz > maxDistSq;
            });
        }
    }

    // ------------------------------------------------------------------
    // Trace worker (worker threads)
    // ------------------------------------------------------------------

    /**
     * One player's trace tick: iterate candidate chunks within XZ range,
     * then candidates within ray distance; frustum cull; crack-detecting
     * DDA. Revealed candidates are removed (RTAX rehide-blocks: false —
     * revealed blocks re-hide when the chunk is resent).
     */
    private static void tracePlayer(PlayerData pd, ServerLevel level,
                                    double eyeX, double eyeY, double eyeZ,
                                    double lookX, double lookY, double lookZ) {
        double traceDistance = OsmiumConfig.raytraceMaxRayDistance;
        double traceDistanceSq = traceDistance * traceDistance;
        int chunkXMin = floor((int) Math.floor(eyeX - traceDistance)) >> 4;
        int chunkZMin = floor((int) Math.floor(eyeZ - traceDistance)) >> 4;
        int chunkXMax = floor((int) Math.floor(eyeX + traceDistance)) >> 4;
        int chunkZMax = floor((int) Math.floor(eyeZ + traceDistance)) >> 4;

        OcclusionReader reader = new OcclusionReader(level);
        int traced = 0;
        int revealed = 0;
        int hiddenLogged = 0;
        int surfaceTraced = 0;

        for (ChunkCandidates cc : pd.chunks.values()) {
            if (cc.chunkX < chunkXMin || cc.chunkX > chunkXMax
                    || cc.chunkZ < chunkZMin || cc.chunkZ > chunkZMax) {
                continue;
            }
            Iterator<ConcurrentHashMap.Entry<BlockPos, Boolean>> it = cc.blocks.entrySet().iterator();
            while (it.hasNext()) {
                ConcurrentHashMap.Entry<BlockPos, Boolean> entry = it.next();
                BlockPos pos = entry.getKey();
                int x = pos.getX();
                int y = pos.getY();
                int z = pos.getZ();
                double dX = eyeX - (x + 0.5);
                double dY = eyeY - (y + 0.5);
                double dZ = eyeZ - (z + 0.5);
                double distSq = dX * dX + dY * dY + dZ * dZ;
                if (distSq > traceDistanceSq) continue;
                traced++;
                if (y >= 55) surfaceTraced++;
                if (isVisible(reader, x, y, z, eyeX, eyeY, eyeZ, lookX, lookY, lookZ)) {
                    it.remove();
                    pd.results.add(new Result(cc, pos));
                    revealed++;
                } else if (OsmiumConfig.raytraceDebug && hiddenLogged < 8) {
                    hiddenLogged++;
                    debugLog("[antixray] hidden-candidate " + x + "," + y + "," + z
                            + " eye=" + Math.round(eyeX) + "," + Math.round(eyeY) + "," + Math.round(eyeZ)
                            + " look=" + Math.round(lookX * 100) / 100.0 + "," + Math.round(lookY * 100) / 100.0 + "," + Math.round(lookZ * 100) / 100.0
                            + " -> " + debugVerdict.get());
                }
            }
            // NOTE: do NOT remove emptied sets from pd.chunks here. The
            // reveals just queued in pd.results reference this cc; dropping
            // the map entry now makes drainResults' freshness check see
            // null != cc and discard every reveal — a fully-visible cluster
            // (chest + hopper) would stay hidden until a neighbor update.
            // prunePeriodically cleans up empty sets after results drain.
        }

        if ((traced > 0 || revealed > 0) && OsmiumConfig.raytraceDebug) {
            debugLog("[antixray] traced player " + pd.player.getGameProfile().name()
                    + ": candidates=" + traced + " surface=" + surfaceTraced
                    + " revealed=" + revealed);
        }
    }

    /**
     * Visibility test with crack detection — port of RTAX
     * BlockOcclusionCulling (MIT, © stonar96). Frustum-culls blocks behind
     * the view plane, then walks Amanatides–Woo from the target block center
     * toward the eye. An occluding voxel only blocks the ray if all three of
     * its face-neighbors toward the target are also occluding — a sliver of
     * air between two blocks ("crack") counts as visible, which is what
     * keeps revealed walls seam-free.
     */
    private static boolean isVisible(OcclusionReader reader, int x, int y, int z,
                                     double eyeX, double eyeY, double eyeZ,
                                     double lookX, double lookY, double lookZ) {
        double centerX = x + 0.5;
        double centerY = y + 0.5;
        double centerZ = z + 0.5;
        double diffX = eyeX - centerX;
        double diffY = eyeY - centerY;
        double diffZ = eyeZ - centerZ;
        double distSq = diffX * diffX + diffY * diffY + diffZ * diffZ;
        if (distSq < 1.0E-8) return true;

        // Frustum cull (RTAX): reject if the block is behind the view plane.
        // RTAX note: should really use (diff - sqrt(3)/2 * dir) * dir.
        if ((diffX - lookX) * lookX + (diffY - lookY) * lookY + (diffZ - lookZ) * lookZ > 0.0) {
            if (OsmiumConfig.raytraceDebug) debugVerdict.set("FRUSTUM");
            return false;
        }

        double dist = Math.sqrt(distSq);
        VoxelWalker walker = new VoxelWalker(x, y, z, centerX, centerY, centerZ,
                diffX / dist, diffY / dist, diffZ / dist, dist);
        int[] ray;
        while ((ray = walker.calculateNext()) != null) {
            if (reader.isOccluding(ray[0], ray[1], ray[2])
                    && checkNearbyBlocks(x, y, z, ray, diffX, diffY, diffZ, reader)) {
                if (OsmiumConfig.raytraceDebug) {
                    // Per-call capture, NOT statics — concurrent player traces
                    // on separate worker threads clobber shared statics.
                    debugVerdict.set("occluder@" + ray[0] + "," + ray[1] + "," + ray[2]
                            + " (" + reader.blockName(ray[0], ray[1], ray[2]) + ")");
                }
                return false;
            }
        }
        if (OsmiumConfig.raytraceDebug) debugVerdict.set("clear");
        return true;
    }

    /** Per-thread verdict of the last isVisible call (debug only). */
    private static final ThreadLocal<String> debugVerdict = ThreadLocal.withInitial(() -> "?");

    // (debug verdicts are per-thread via debugVerdict — no shared statics)

    /**
     * RTAX checkNearbyBlocks port (MIT, © stonar96): for an occluding voxel
     * on the ray, determine the eye-side quadrant, then probe the 3
     * face-neighbors (most-likely-air first) and the voxel one step beyond
     * each. Any connected non-occluding path to the target -> visible.
     */
    private static boolean checkNearbyBlocks(int targetX, int targetY, int targetZ, int[] ray,
                                             double diffX, double diffY, double diffZ,
                                             OcclusionReader reader) {
        int[][] nearbyBlocks;
        int incAxis;
        int incDir;
        double absDiffX = Math.abs(diffX);
        double absDiffY = Math.abs(diffY);
        double absDiffZ = Math.abs(diffZ);
        double rayDiffX = ray[0] - targetX;
        double rayDiffY = ray[1] - targetY;
        double rayDiffZ = ray[2] - targetZ;

        if (absDiffX > absDiffY) {
            if (absDiffZ > absDiffX) {
                double factor = divide(diffZ, rayDiffZ);
                double projX = multiply(factor, rayDiffX) - diffX;
                double projY = multiply(factor, rayDiffY) - diffY;
                if (projX > 0.0) {
                    nearbyBlocks = projY > 0.0 ? NB_Z_PLANE_XNEG_YNEG : NB_Z_PLANE_XNEG_YPOS;
                } else {
                    nearbyBlocks = projY > 0.0 ? NB_Z_PLANE_XPOS_YNEG : NB_Z_PLANE_XPOS_YPOS;
                }
                if (diffZ > 0.0) { incAxis = 2; incDir = -1; } else { incAxis = 2; incDir = 1; }
            } else {
                double factor = divide(diffX, rayDiffX);
                double projY = multiply(factor, rayDiffY) - diffY;
                double projZ = multiply(factor, rayDiffZ) - diffZ;
                if (projY > 0.0) {
                    nearbyBlocks = projZ > 0.0 ? NB_X_PLANE_YNEG_ZNEG : NB_X_PLANE_YNEG_ZPOS;
                } else {
                    nearbyBlocks = projZ > 0.0 ? NB_X_PLANE_YPOS_ZNEG : NB_X_PLANE_YPOS_ZPOS;
                }
                if (diffX > 0.0) { incAxis = 0; incDir = -1; } else { incAxis = 0; incDir = 1; }
            }
        } else if (absDiffY > absDiffZ) {
            double factor = divide(diffY, rayDiffY);
            double projZ = multiply(factor, rayDiffZ) - diffZ;
            double projX = multiply(factor, rayDiffX) - diffX;
            if (projZ > 0.0) {
                nearbyBlocks = projX > 0.0 ? NB_Y_PLANE_ZNEG_XNEG : NB_Y_PLANE_ZNEG_XPOS;
            } else {
                nearbyBlocks = projX > 0.0 ? NB_Y_PLANE_ZPOS_XNEG : NB_Y_PLANE_ZPOS_XPOS;
            }
            if (diffY > 0.0) { incAxis = 1; incDir = -1; } else { incAxis = 1; incDir = 1; }
        } else {
            double factor = divide(diffZ, rayDiffZ);
            double projX = multiply(factor, rayDiffX) - diffX;
            double projY = multiply(factor, rayDiffY) - diffY;
            if (projX > 0.0) {
                nearbyBlocks = projY > 0.0 ? NB_Z_PLANE_XNEG_YNEG : NB_Z_PLANE_XNEG_YPOS;
            } else {
                nearbyBlocks = projY > 0.0 ? NB_Z_PLANE_XPOS_YNEG : NB_Z_PLANE_XPOS_YPOS;
            }
            if (diffZ > 0.0) { incAxis = 2; incDir = -1; } else { incAxis = 2; incDir = 1; }
        }

        for (int[] step : nearbyBlocks) {
            ray[0] += step[0];
            ray[1] += step[1];
            ray[2] += step[2];

            if (reader.isOccluding(ray[0], ray[1], ray[2])) continue;

            ray[incAxis] += incDir;

            if ((ray[0] == targetX && ray[1] == targetY && ray[2] == targetZ)
                    || !reader.isOccluding(ray[0], ray[1], ray[2])) {
                return false;
            }

            ray[incAxis] -= incDir;
        }

        return true;
    }

    // Face-neighbor probe orders (RTAX NEARBY_BLOCKS_* tables): 3 voxels
    // sharing a face with the occluder, most-likely-air-gap first.
    private static final int[][] NB_X_PLANE_YNEG_ZNEG = {{0, -1, 0}, {0, 0, -1}, {0, 1, 0}};
    private static final int[][] NB_X_PLANE_YNEG_ZPOS = {{0, -1, 0}, {0, 0, 1}, {0, 1, 0}};
    private static final int[][] NB_X_PLANE_YPOS_ZNEG = {{0, 1, 0}, {0, 0, -1}, {0, -1, 0}};
    private static final int[][] NB_X_PLANE_YPOS_ZPOS = {{0, 1, 0}, {0, 0, 1}, {0, -1, 0}};
    private static final int[][] NB_Y_PLANE_ZNEG_XNEG = {{0, 0, -1}, {-1, 0, 0}, {0, 0, 1}};
    private static final int[][] NB_Y_PLANE_ZNEG_XPOS = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}};
    private static final int[][] NB_Y_PLANE_ZPOS_XNEG = {{0, 0, 1}, {-1, 0, 0}, {0, 0, -1}};
    private static final int[][] NB_Y_PLANE_ZPOS_XPOS = {{0, 0, 1}, {1, 0, 0}, {0, 0, -1}};
    private static final int[][] NB_Z_PLANE_XNEG_YNEG = {{0, -1, 0}, {-1, 0, 0}, {0, 1, 0}};
    private static final int[][] NB_Z_PLANE_XNEG_YPOS = {{0, 1, 0}, {-1, 0, 0}, {0, -1, 0}};
    private static final int[][] NB_Z_PLANE_XPOS_YNEG = {{0, -1, 0}, {1, 0, 0}, {0, 1, 0}};
    private static final int[][] NB_Z_PLANE_XPOS_YPOS = {{0, 1, 0}, {1, 0, 0}, {0, -1, 0}};

    private static double divide(double dividend, double divisor) {
        return (divisor == 0.0 && !Double.isNaN(dividend) ? Math.copySign(1.0, dividend) : dividend) / divisor;
    }

    private static double multiply(double factor, double value) {
        return (value == 0.0 ? Math.signum(factor) : factor) * value;
    }

    /**
     * Amanatides–Woo voxel walker — port of RTAX BlockIterator (MIT,
     * © stonar96; algorithm: Amanatides & Woo). Zero-allocation: reuses one
     * int[3]. The start voxel (the target) is NOT yielded; the walk steps
     * off it first, bounded by the distance budget.
     */
    private static final class VoxelWalker {
        private int x;
        private int y;
        private int z;
        private final int stepX;
        private final int stepY;
        private final int stepZ;
        private final double tMax;
        private double tMaxX;
        private double tMaxY;
        private double tMaxZ;
        private final double tDeltaX;
        private final double tDeltaY;
        private final double tDeltaZ;
        private final int[] ref = new int[3];

        VoxelWalker(int vx, int vy, int vz, double startX, double startY, double startZ,
                    double dirX, double dirY, double dirZ, double distance) {
            this.x = vx;
            this.y = vy;
            this.z = vz;
            this.tMax = distance;
            this.stepX = dirX < 0.0 ? -1 : 1;
            this.stepY = dirY < 0.0 ? -1 : 1;
            this.stepZ = dirZ < 0.0 ? -1 : 1;
            this.tMaxX = dirX == 0.0 ? Double.POSITIVE_INFINITY : (x + (stepX + 1) / 2 - startX) / dirX;
            this.tMaxY = dirY == 0.0 ? Double.POSITIVE_INFINITY : (y + (stepY + 1) / 2 - startY) / dirY;
            this.tMaxZ = dirZ == 0.0 ? Double.POSITIVE_INFINITY : (z + (stepZ + 1) / 2 - startZ) / dirZ;
            this.tDeltaX = 1.0 / Math.abs(dirX);
            this.tDeltaY = 1.0 / Math.abs(dirY);
            this.tDeltaZ = 1.0 / Math.abs(dirZ);
            this.ref[0] = x;
            this.ref[1] = y;
            this.ref[2] = z;
        }

        /** Steps to the next voxel; returns null when the distance budget is exhausted. */
        int[] calculateNext() {
            if (tMaxX < tMaxY) {
                if (tMaxZ < tMaxX) {
                    if (tMaxZ <= tMax) {
                        z += stepZ;
                        ref[0] = x; ref[1] = y; ref[2] = z;
                        tMaxZ += tDeltaZ;
                    } else {
                        return null;
                    }
                } else {
                    if (tMaxX <= tMax) {
                        if (tMaxZ == tMaxX) {
                            z += stepZ;
                            tMaxZ += tDeltaZ;
                        }
                        x += stepX;
                        ref[0] = x; ref[1] = y; ref[2] = z;
                        tMaxX += tDeltaX;
                    } else {
                        return null;
                    }
                }
            } else if (tMaxY < tMaxZ) {
                if (tMaxY <= tMax) {
                    if (tMaxX == tMaxY) {
                        x += stepX;
                        tMaxX += tDeltaX;
                    }
                    y += stepY;
                    ref[0] = x; ref[1] = y; ref[2] = z;
                    tMaxY += tDeltaY;
                } else {
                    return null;
                }
            } else {
                if (tMaxZ <= tMax) {
                    if (tMaxX == tMaxZ) {
                        x += stepX;
                        tMaxX += tDeltaX;
                    }
                    if (tMaxY == tMaxZ) {
                        y += stepY;
                        tMaxY += tDeltaY;
                    }
                    z += stepZ;
                    ref[0] = x; ref[1] = y; ref[2] = z;
                    tMaxZ += tDeltaZ;
                } else {
                    return null;
                }
            }
            return ref;
        }
    }

    /**
     * Occlusion predicate with per-chunk/section caches (RTAX
     * CachedSectionBlockOcclusionGetter port). Reads live chunk sections
     * off-thread like RTAX does; unloaded chunks fail CLOSED (UNLOADED_
     * OCCLUDING) — a ray crossing unloaded terrain cannot confirm LOS.
     */
    private static final class OcclusionReader {
        private static final boolean UNLOADED_OCCLUDING = true;
        private final ServerLevel level;
        private LevelChunk chunk;
        private LevelChunkSection section;
        private int lastChunkX = Integer.MIN_VALUE;
        private int lastChunkZ = Integer.MIN_VALUE;
        private int lastSectionY = Integer.MIN_VALUE;

        OcclusionReader(ServerLevel level) {
            this.level = level;
        }

        boolean isOccluding(int x, int y, int z) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            if (chunk == null || lastChunkX != chunkX || lastChunkZ != chunkZ) {
                lastChunkX = chunkX;
                lastChunkZ = chunkZ;
                lastSectionY = Integer.MIN_VALUE;
                section = null;
                chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) return UNLOADED_OCCLUDING;
            }
            int sectionY = y >> 4;
            if (lastSectionY != sectionY) {
                lastSectionY = sectionY;
                section = null;
                int min = chunk.getMinSectionY();
                if (sectionY < min || sectionY >= min + chunk.getSectionsCount()) return false;
                LevelChunkSection s = chunk.getSections()[sectionY - min];
                if (s == null || s.hasOnlyAir()) { // Paper quirk: recalcBlockCounts may transiently reset counts
                    section = null;
                    return false;
                }
                section = s;
            }
            if (section == null) return chunk == null && UNLOADED_OCCLUDING;
            try {
                return section.getBlockState(x & 15, y & 15, z & 15).isSolidRender();
            } catch (MissingPaletteEntryException e) {
                return false; // chunk mutating concurrently: fail open (RTAX returns AIR)
            }
        }

        /** Debug-only: block id at a voxel (empty string when unavailable). */
        String blockName(int x, int y, int z) {
            try {
                int chunkX = x >> 4;
                int chunkZ = z >> 4;
                LevelChunk c = chunk != null && lastChunkX == chunkX && lastChunkZ == chunkZ
                        ? chunk : level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (c == null) return "unloaded";
                int min = c.getMinSectionY();
                int sectionY = y >> 4;
                if (sectionY < min || sectionY >= min + c.getSectionsCount()) return "out-of-range";
                LevelChunkSection s = c.getSections()[sectionY - min];
                if (s == null || s.hasOnlyAir()) return "air-section";
                BlockState st = s.getBlockState(x & 15, y & 15, z & 15);
                return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
            } catch (Exception e) {
                return "?";
            }
        }
    }

    // Lazily resolved target blocks (registries may not be frozen at config load)
    private static volatile Set<Block> resolvedTargets = null;

    private static Set<Block> targets() {
        Set<Block> set = resolvedTargets;
        if (set == null) {
            synchronized (OsmiumOcclusion.class) {
                set = resolvedTargets;
                if (set == null) {
                    set = new HashSet<>();
                    for (String name : OsmiumConfig.raytraceTargetBlocks) {
                        Block b = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(
                                name.trim().toLowerCase(Locale.ROOT)));
                        if (b != null) {
                            set.add(b);
                        } else {
                            OsmiumOcclusion.LOGGER.error("[Osmium] raytrace-hiding: unknown block '" + name + "'");
                        }
                    }
                    resolvedTargets = set;
                }
            }
        }
        return set;
    }

    /** Read-only view of resolved target blocks. */
    public static Set<Block> targetBlocks() {
        return targets();
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    // ------------------------------------------------------------------
    // Block entity occlusion (hook used by the ChunkMap patch)
    // ------------------------------------------------------------------

    /**
     * Block-entity variant of entity occlusion: chests, furnaces, item
     * frames' holders etc. rendered from chunk data get stripped from the
     * packet when terrain blocks all lines of sight to the receiving player.
     * The receiving player is passed in from the per-packet chunk info —
     * the CURRENT_PLAYER ThreadLocal is already cleared by the time the
     * block-entity list is serialized.
     */
    public static boolean shouldHideBlockEntity(ServerLevel level, ServerPlayer player, BlockPos pos) {
        boolean verdict = shouldHideBlockEntity0(level, player, pos);
        if (OsmiumConfig.entityOcclusionDebug && net.minecraft.server.MinecraftServer.getServer() != null
                && net.minecraft.server.MinecraftServer.getServer().getTickCount() % 20 == 0) {
            double d = Math.sqrt(player.getEyePosition().distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            LOGGER.info("[BE-debug] " + pos.toShortString() + " sec=" + (pos.getY() >> 4)
                    + " viewer=" + player.getGameProfile().name()
                    + " dist=" + String.format("%.1f", d) + " hide=" + verdict);
        }
        return verdict;
    }

    private static boolean shouldHideBlockEntity0(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (!OsmiumConfig.entityOcclusionEnabled && !OsmiumConfig.chunkHidingEnabled) return false;
        if (player == null || player.level() != level) return false;

        // Chunk-hiding consistency: if this viewer's packet has the block's
        // section replaced by fake deepslate, the block entity must never
        // ship either — a container entry inside "solid rock" is exactly the
        // leak stash-finders (StorageESP / chest-cluster scanners) exploit.
        // No LOS check: the client believes the section is solid.
        if (OsmiumConfig.chunkHidingEnabled
                && !OsmiumConfig.chunkHidingDisableInSpawnDim
                && !org.osmium.OsmiumSpawnDim.isSpawnDimension(level)
                && (pos.getY() >> 4) < (OsmiumConfig.chunkHidingYThreshold >> 4)
                && !OsmiumChunkProcessor.withinProximityReveal(player, pos)) {
            return true;
        }

        if (!OsmiumConfig.entityOcclusionEnabled) return false;

        Vec3 eye = player.getEyePosition();
        double dx = pos.getX() + 0.5 - eye.x;
        double dy = pos.getY() + 0.5 - eye.y;
        double dz = pos.getZ() + 0.5 - eye.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        double maxDist = OsmiumConfig.entityOcclusionMaxDistance;
        // Default-DENY beyond the confirmed-visible radius (RaycastedAntiESP
        // model): an unverified distant block entity ships stripped rather
        // than leaked. Chests/spawners reappear once the player is close
        // enough for a definitive LOS verdict.
        if (distSq > maxDist * maxDist) {
            double beMax = OsmiumConfig.entityOcclusionBeMaxDistance;
            return beMax <= 0 || distSq > beMax * beMax;
        }
        if (distSq < 9.0) return false;

        // Candidate-model container verdict: when raytrace-hiding is active,
        // the container BLOCK itself is a target (chests/barrels/etc. in the
        // blocks list). The packet shipped it as fake stone and registered a
        // candidate; until that candidate is revealed to THIS player, its
        // block entity must not ship either.
        if (OsmiumConfig.raytraceHidingEnabled) {
            long ck = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
            PlayerData pd = playerData.get(player.getUUID());
            if (pd != null) {
                ChunkCandidates cc = pd.chunks.get(ck);
                if (cc != null) {
                    Boolean hidden = cc.blocks.get(pos);
                    if (hidden != null) return hidden; // revealed candidate: ship the BE
                    // Not a candidate: either a buried target (client sees
                    // fake stone — strip) or not a target at all (generic
                    // occlusion below decides).
                    if (targets().contains(level.getBlockState(pos).getBlock())) return true;
                }
            }
        }

        return !canSee(level, eye, pos);
    }

    // ------------------------------------------------------------------
    // Entity occlusion
    // ------------------------------------------------------------------

    /**
     * entityId -> (pairKey -> packed verdict: abs(value)-1 = tick, sign =
     * visible/occluded). Concurrent maps REQUIRED: workers publish verdicts
     * while the main-thread TTL sweep iterates. The previous fastutil maps
     * corrupted under that race ("wrapped" null NPE) and hard-crashed the
     * server tick loop.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<Long, Long>> entityVerdicts =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Returns true when the entity must NOT be tracked for this player
     * because terrain occludes it. Main thread only (vanilla clip usage).
     */
    public static boolean isEntityOccluded(ServerPlayer player, Entity entity) {
        if (!OsmiumConfig.entityOcclusionEnabled) return false;
        if (entity instanceof ServerPlayer) return false;          // never occlude players
        if (entity.level() != player.level()) return false;

        Vec3 eye = player.getEyePosition();
        Vec3 epos = entity.position();
        double distSq = epos.distanceToSqr(eye);
        double maxDist = OsmiumConfig.entityOcclusionMaxDistance;
        if (distSq > maxDist * maxDist) return false;              // far: distance culling handles it
        if (distSq < 9.0) return false;                            // adjacent: always visible

        // Keyed by UUIDs: entity int ids are recycled after despawn, which
        // could hand a stale verdict to an unrelated new entity.
        long entityKey = entity.getUUID().getMostSignificantBits() ^ entity.getUUID().getLeastSignificantBits();
        long pairKey = player.getUUID().getMostSignificantBits() ^ player.getUUID().getLeastSignificantBits();
        long nowTick = player.level().getGameTime();

        java.util.concurrent.ConcurrentHashMap<Long, Long> perEntity = entityVerdicts.get(entityKey);
        long previous = 0;
        if (perEntity != null) {
            Long boxed = perEntity.get(pairKey);
            previous = boxed == null ? 0L : boxed;
            if (previous != 0 && nowTick - (Math.abs(previous) - 1) < OsmiumConfig.entityOcclusionCheckIntervalTicks) {
                return previous < 0;
            }
        }

        // Stale verdict: queue an off-main-thread recompute using our own DDA
        // walker (loaded chunks only, fails open). The STALE verdict stays
        // authoritative until fresh data lands — returning "visible" here
        // made entities flicker on every interval boundary.
        scheduleTrace(player, entity, pairKey);
        return previous != 0 && previous < 0;
    }

    private static void scheduleTrace(ServerPlayer player, Entity entity, long pairKey) {
        final long entityKey = entity.getUUID().getMostSignificantBits() ^ entity.getUUID().getLeastSignificantBits();
        // Dedup per player+entity pair (not just player — that starved all
        // but one entity of traces and amplified flicker).
        final long traceKey = pairKey ^ (entityKey * 0x9E3779B97F4A7C15L);
        if (!pendingTraces.add(traceKey)) return;                  // already queued
        if (pendingTraces.size() > MAX_PENDING_TRACES) {           // overload: fail open
            pendingTraces.remove(traceKey);
            return;
        }
        final ServerLevel level = (ServerLevel) entity.level();
        final Vec3 eye = player.getEyePosition();
        final var bb = entity.getBoundingBox();
        final double midY = bb.minY + (bb.maxY - bb.minY) * 0.5;
        final Vec3[] targets = {
                bb.getCenter(),
                new Vec3(bb.minX + 0.1, midY, bb.minZ + 0.1),
                new Vec3(bb.maxX - 0.1, midY, bb.maxZ - 0.1),
        };
        final long nowTick = level.getGameTime();

        try {
            workers().execute(() -> {
                try {
                    boolean visible = false;
                    for (Vec3 t : targets) {
                        if (rayClear(level, eye, t)) { visible = true; break; }
                    }
                    boolean occluded = !visible;
                    entityVerdicts.computeIfAbsent(entityKey, k -> new java.util.concurrent.ConcurrentHashMap<>())
                            .put(pairKey, (occluded ? -1L : 1L) * (nowTick + 1));
                } catch (Exception ignored) {
                    // any failure: no verdict stored -> entity stays visible
                } finally {
                    pendingTraces.remove(traceKey);
                }
            });
        } catch (Exception rejected) {
            pendingTraces.remove(traceKey);
        }
    }

    private static void sweepEntityVerdicts(long nowTick) {
        if (entityVerdicts.isEmpty()) return;
        long ttl = OsmiumConfig.entityOcclusionCheckIntervalTicks * 4L + 40L;
        // Weakly-consistent iteration over concurrent maps: safe against
        // concurrent worker publishes by design.
        entityVerdicts.entrySet().removeIf(outer -> {
            var perEntity = outer.getValue();
            perEntity.entrySet().removeIf(e ->
                    nowTick - (Math.abs(e.getValue()) - 1) > ttl);
            return perEntity.isEmpty();
        });
    }

    /**
     * Worker pool, sized from config (raytrace-hiding.worker-threads). Created
     * lazily on first tick because config loads after class init.
     */
    private static volatile ExecutorService workerPool;
    private static ExecutorService workers() {
        ExecutorService w = workerPool;
        if (w == null) {
            synchronized (OsmiumOcclusion.class) {
                w = workerPool;
                if (w == null) {
                    int n = Math.max(1, OsmiumConfig.occlusionWorkerThreads);
                    w = Executors.newFixedThreadPool(n, r -> {
                        Thread t = new Thread(r, "Osmium-Occlusion-Worker");
                        t.setDaemon(true);
                        return t;
                    });
                    workerPool = w;
                }
            }
        }
        return w;
    }

    private static final int MAX_PENDING_TRACES = 128;
    private static final Set<Long> pendingTraces = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    // Shared ray helpers (entity occlusion + BE fallback)
    // ------------------------------------------------------------------

    /**
     * True if a straight path from eye to one of the block's sample points
     * reaches it without another opaque block in between.
     */
    static boolean canSee(ServerLevel level, Vec3 eye, BlockPos target) {
        int samples = Math.min(4, Math.max(1, OsmiumConfig.raytraceSamplesPerBlock));
        for (int s = 0; s < samples; s++) {
            Vec3 t = switch (s) {
                case 0 -> new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
                case 1 -> new Vec3(target.getX() + 0.5, target.getY() + 1.01, target.getZ() + 0.5);
                case 2 -> new Vec3(target.getX() + 0.06, target.getY() + 0.94, target.getZ() + 0.06);
                default -> new Vec3(target.getX() + 0.94, target.getY() + 0.94, target.getZ() + 0.94);
            };
            if (rayClearConfirm(level, eye, t)) return true;
        }
        return false;
    }

    /** Opacity probe abstraction so the traversal is unit-testable. */
    public interface OpacityFn {
        boolean isOpaque(int x, int y, int z);
    }

    /** Amanatides–Woo voxel DDA; true if nothing opaque blocks the segment. */
    static boolean rayClear(ServerLevel level, Vec3 from, Vec3 to) {
        return traverse(from, to, (x, y, z) -> {
            BlockState state = level.getBlockStateIfLoaded(new BlockPos(x, y, z));
            if (state == null) return false; // unloaded: fail open
            return state.isSolidRender();
        });
    }

    /**
     * Visibility variant: unloaded regions count as OPAQUE. A ray crossing
     * the edge of loaded terrain cannot confirm line of sight — treating
     * the void as transparent let rays "see" through unloaded chunks and
     * wrongly mark out-of-sight targets as revealed.
     */
    static boolean rayClearConfirm(ServerLevel level, Vec3 from, Vec3 to) {
        return traverse(from, to, (x, y, z) -> {
            BlockState state = level.getBlockStateIfLoaded(new BlockPos(x, y, z));
            if (state == null) return true; // unloaded: cannot confirm LOS
            return state.isSolidRender();
        });
    }

    /**
     * Pure Amanatides–Woo traversal: steps voxels from `from` toward `to`,
     * stopping at the target voxel (returns true) or at the first opaque
     * voxel (returns false). Unit-testable without a world.
     */
    public static boolean traverse(Vec3 from, Vec3 to, OpacityFn opacity) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        if (dx * dx + dy * dy + dz * dz < 1.0E-8) return true;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        int x = (int) Math.floor(from.x);
        int y = (int) Math.floor(from.y);
        int z = (int) Math.floor(from.z);

        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;

        double invDx = dx == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dx);
        double invDy = dy == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dy);
        double invDz = dz == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dz);

        double tMaxX = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? (x + 1 - from.x) : (from.x - x)) / Math.abs(dx));
        double tMaxY = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? (y + 1 - from.y) : (from.y - y)) / Math.abs(dy));
        double tMaxZ = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? (z + 1 - from.z) : (from.z - z)) / Math.abs(dz));

        int targetX = (int) Math.floor(to.x);
        int targetY = (int) Math.floor(to.y);
        int targetZ = (int) Math.floor(to.z);

        // Start voxel == target voxel: nothing lies between them.
        if (x == targetX && y == targetY && z == targetZ) return true;

        int steps = (int) Math.ceil(dist) + 1;
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

        for (int i = 0; i < steps; i++) {
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                x += stepX; tMaxX += invDx;
            } else if (tMaxY <= tMaxZ) {
                y += stepY; tMaxY += invDy;
            } else {
                z += stepZ; tMaxZ += invDz;
            }

            if (x == targetX && y == targetY && z == targetZ) return true; // reached target voxel

            mpos.set(x, y, z);
            if (x == targetX && y == targetY && z == targetZ) break;
            if (opacity.isOpaque(x, y, z)) return false; // blocked
        }
        return true;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < (double) i ? i - 1 : i;
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Occlusion");
}
