package org.osmium.anticheat;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Raycast-based occlusion engine. See design notes at the top of each section.
 *
 * BLOCK occlusion (raytrace antixray):
 *   Per-chunk visibility bitsets marking which target blocks (ores) have line
 *   of sight to at least one nearby player. Computed OFF the main thread by a
 *   small worker pool, budgeted by an in-flight cap. The chunk processor only
 *   performs O(1) cache lookups at packet time and rewrites just the unseen
 *   entries. Cache invalidation: block changes (own + bordering chunks) and a
 *   slow periodic refresh for drift. Chunks without players in range evicted.
 *
 * ENTITY occlusion:
 *   Main-thread vanilla clip() from player eyes to the entity bounding box,
 *   with a verdict TTL cache so each entity+player pair traces at most once
 *   per check interval. Players are never occluded.
 *
 * Everything fails open (visible): a stale "visible" verdict costs nothing,
 * a wrongly hidden one is corrected on the next refresh — never a desync
 * risk for legitimate players.
 */
public final class OsmiumOcclusion {

    private OsmiumOcclusion() {}

    // ------------------------------------------------------------------
    // Block occlusion state
    // ------------------------------------------------------------------

    private static final Long2ObjectOpenHashMap<VisibilityData> visibilityCache = new Long2ObjectOpenHashMap<>();

    // Striped locks: 200-player packet traffic hits these constantly; a single
    // monitor would serialize every chunk send. Stripe by chunk key hash.
    private static final int STRIPES = 16;
    private static final Object[] STRIPE_LOCKS = new Object[STRIPES];
    static {
        for (int i = 0; i < STRIPES; i++) STRIPE_LOCKS[i] = new Object();
    }
    private static Object stripe(long key) {
        int h = (int) (key ^ (key >>> 32));
        h ^= h >>> 16;
        return STRIPE_LOCKS[h & (STRIPES - 1)];
    }
    private static VisibilityData cacheGet(long key) {
        synchronized (stripe(key)) { return visibilityCache.get(key); }
    }
    private static void cachePut(long key, VisibilityData data) {
        synchronized (stripe(key)) { visibilityCache.put(key, data); }
    }
    private static void cacheRemove(long key) {
        synchronized (stripe(key)) { visibilityCache.remove(key); }
    }
    private static final ConcurrentLinkedQueue<ChunkJob> dirtyChunks = new ConcurrentLinkedQueue<>();
    private static final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** Keys invalidated while a compute was running: worker re-runs them. */
    private static final Set<Long> invalidatedWhileFlying = ConcurrentHashMap.newKeySet();

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

    private record ChunkJob(ServerLevel level, long chunkKey) {}

    /** Per-chunk visibility: one 4096-bit set per section, flattened into long[64] blocks. */
    static final class VisibilityData {
        private final long[] seen;
        private final int minSectionY;
        volatile boolean ready;

        VisibilityData(int minSectionY, int sectionsCount) {
            this.minSectionY = minSectionY;
            this.seen = new long[sectionsCount * 64];
        }

        boolean isSeen(int sectionY, int packedBlockIndex) {
            int base = (sectionY - minSectionY) * 64;
            if (base < 0 || base >= seen.length) return true; // out of range: fail open
            return (seen[base + (packedBlockIndex >>> 6)] & (1L << (packedBlockIndex & 63))) != 0;
        }

        void markSeen(int sectionY, int packedBlockIndex) {
            int base = (sectionY - minSectionY) * 64;
            if (base < 0 || base >= seen.length) return;
            seen[base + (packedBlockIndex >>> 6)] |= (1L << (packedBlockIndex & 63));
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

    /** Config reload hook: force target re-resolution and drop caches. */
    public static void clearCaches() {
        resolvedTargets = null;
        for (Object lock : STRIPE_LOCKS) {
            synchronized (lock) { visibilityCache.clear(); }
        }
        dirtyChunks.clear();
        inFlight.clear();
        pendingTraces.clear();
        entityVerdicts.clear();
    }

    // ------------------------------------------------------------------
    // Block occlusion — hooks
    // ------------------------------------------------------------------

    /**
     * Packet-time lookup. Fast path: single map access + bit test.
     * @param sectionY section Y coordinate
     * @param packedBlockIndex 0..4095, y<<8 | z<<4 | x within the section
     */
    public static boolean shouldHideBlock(ServerLevel level, long chunkKey,
                                          int sectionY, int packedBlockIndex) {
        if (!OsmiumConfig.raytraceHidingEnabled || targets().isEmpty()) return false;
        VisibilityData data;
        data = cacheGet(chunkKey);
        if (data == null || !data.ready) return false; // fail open until computed
        return !data.isSeen(sectionY, packedBlockIndex);
    }

    /** Marks a chunk dirty for recomputation. Any thread. */
    public static void invalidateChunk(ServerLevel level, long chunkKey) {
        if (!OsmiumConfig.raytraceHidingEnabled) return;
        cacheRemove(chunkKey);
        enqueue(level, chunkKey);
    }

    /** Block change hook: invalidate own chunk + touched neighbors. */
    public static void onBlockChanged(Level level, BlockPos pos) {
        if (!OsmiumConfig.raytraceHidingEnabled || !(level instanceof ServerLevel sl)) return;
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        invalidateChunk(sl, chunkKey(cx, cz));

        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        if (localX == 0)  invalidateChunk(sl, chunkKey(cx - 1, cz));
        if (localX == 15) invalidateChunk(sl, chunkKey(cx + 1, cz));
        if (localZ == 0)  invalidateChunk(sl, chunkKey(cx, cz - 1));
        if (localZ == 15) invalidateChunk(sl, chunkKey(cx, cz + 1));
    }

    private static void enqueue(ServerLevel level, long chunkKey) {
        if (dirtyChunks.size() > 4096) return; // bounded backlog
        Long boxed = chunkKey;
        // If a compute for this key is currently running, remember that its
        // result will be stale the moment it lands — the worker re-runs it.
        if (!inFlight.add(boxed)) {
            invalidatedWhileFlying.add(boxed);
            return;
        }
        dirtyChunks.add(new ChunkJob(level, chunkKey));
    }

    // ------------------------------------------------------------------
    // Tick driver (main thread)
    // ------------------------------------------------------------------

    private static long lastRefreshTick = 0;
    private static long lastVerdictSweep = 0;

    // Async entity occlusion: pairKeys with a trace queued/running
    private static final Set<Long> pendingTraces = ConcurrentHashMap.newKeySet();
    private static final int MAX_PENDING_TRACES = 128;

    public static void tick(MinecraftServer server) {
        boolean blocks = OsmiumConfig.raytraceHidingEnabled && !targets().isEmpty();
        boolean entities = OsmiumConfig.entityOcclusionEnabled;
        if (!blocks && !entities) return;

        long nowTick = server.getTickCount();

        if (blocks) {
            if (nowTick - lastRefreshTick >= OsmiumConfig.raytraceRefreshSeconds * 20L) {
                lastRefreshTick = nowTick;
                refreshCycle(server);
            }
            dispatchJobs(server);
        }

        if (entities && nowTick - lastVerdictSweep >= 20) {
            lastVerdictSweep = nowTick;
            sweepEntityVerdicts(nowTick);
        }
    }

    /** Submits up to the in-flight cap worth of jobs to the worker pool. */
    private static void dispatchJobs(MinecraftServer server) {
        int cap = Math.max(1, OsmiumConfig.raytraceChecksPerTick);
        while (inFlight.size() < cap) {
            ChunkJob job = dirtyChunks.poll();
            if (job == null) break;

            // Only compute chunks that still matter
            if (!hasPlayerInRange(job.level(), job.chunkKey())) {
                cacheRemove(job.chunkKey());
                inFlight.remove(job.chunkKey());
                continue;
            }

            workers().execute(() -> {
                long key = job.chunkKey();
                try {
                    VisibilityData data;
                    boolean rerun;
                    do {
                        data = computeVisibility(job.level(), key);
                        rerun = invalidatedWhileFlying.remove(key);
                    } while (rerun);
                    if (data != null) {
                        cachePut(key, data);
                    }
                } catch (Exception e) {
                    LOGGER.error("[Osmium] occlusion compute failed for chunk " + key
                            + ": " + e.getMessage());
                } finally {
                    inFlight.remove(key);
                    // A last-moment invalidation may have raced past; requeue once.
                    if (invalidatedWhileFlying.remove(key)) {
                        enqueue(job.level(), key);
                    }
                }

            });
        }
    }

    /** Periodic refresh: re-enqueue live caches, evict dead ones. Bounded work. */
    private static void refreshCycle(MinecraftServer server) {
        List<Long> keys;
        synchronized (visibilityCache) {
            keys = new ArrayList<>(visibilityCache.keySet());
        }
        int enqueued = 0;
        for (long key : keys) {
            boolean anyRange = false;
            for (ServerLevel lvl : server.getAllLevels()) {
                if (hasPlayerInRange(lvl, key)) { anyRange = true; break; }
            }
            if (!anyRange) {
                cacheRemove(key);
            } else if (enqueued < 256) {
                // find owning level
                for (ServerLevel lvl : server.getAllLevels()) {
                    if (lvl.getChunkSource().getChunkNow((int) key, (int) (key >> 32)) != null) {
                        enqueue(lvl, key);
                        enqueued++;
                        break;
                    }
                }
            }
        }
    }

    private static boolean hasPlayerInRange(ServerLevel level, long chunkKey) {
        int cx = (int) chunkKey;
        int cz = (int) (chunkKey >> 32);
        double centerX = (cx << 4) + 8;
        double centerZ = (cz << 4) + 8;
        double rangeSq = sq(OsmiumConfig.raytraceMaxRayDistance + 32);
        List<ServerPlayer> players = level.players();
        for (int i = 0; i < players.size(); i++) {
            ServerPlayer p = players.get(i);
            double dx = p.getX() - centerX;
            double dz = p.getZ() - centerZ;
            if (dx * dx + dz * dz <= rangeSq) return true;
        }
        return false;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
    }

    private static double sq(double d) { return d * d; }

    // ------------------------------------------------------------------
    // Visibility computation (worker threads — loaded chunks only)
    // ------------------------------------------------------------------

    private static VisibilityData computeVisibility(ServerLevel level, long chunkKey) {
        var chunk = level.getChunkSource().getChunkNow((int) chunkKey, (int) (chunkKey >> 32));
        if (chunk == null) return null;

        int minSectionY = chunk.getMinSectionY();
        int sectionsCount = chunk.getSectionsCount();

        // Gather candidate positions (target blocks present in this chunk)
        ArrayList<BlockPos> candidates = new ArrayList<>(64);
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (int idx = 0; idx < sectionsCount; idx++) {
            var section = chunk.getSection(idx);
            if (section == null || section.hasOnlyAir()) continue;
            int sy = minSectionY + idx;
            int yBase = sy << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = section.getBlockState(x, y, z);
                        if (st.isAir() || !targets().contains(st.getBlock())) continue;
                        candidates.add(mpos.set((chunk.getPos().x() << 4) + x, yBase + y,
                                (chunk.getPos().z() << 4) + z).immutable());
                    }
                }
            }
        }

        VisibilityData data = new VisibilityData(minSectionY, sectionsCount);

        if (candidates.isEmpty()) {
            data.ready = true;
            return data;
        }

        // Qualifying player eyes: any player within ray distance of the CHUNK
        // CENTER. Never sample candidates here — a sparse-probe miss would
        // wrongly mark every block hidden for nearby players.
        List<Vec3> eyes = new ArrayList<>(4);
        double maxDistSq = sq(OsmiumConfig.raytraceMaxRayDistance);
        double centerX = (chunk.getPos().x() << 4) + 8;
        double centerZ = (chunk.getPos().z() << 4) + 8;
        for (ServerPlayer p : level.players()) {
            Vec3 eye = p.getEyePosition();
            double ddx = centerX - eye.x;
            double ddz = centerZ - eye.z;
            // horizontal check + generous vertical margin
            if (ddx * ddx + ddz * ddz <= maxDistSq) {
                eyes.add(eye);
            }
        }

        if (eyes.isEmpty()) {
            data.ready = true;
            return data; // nobody close enough: everything stays hidden
        }

        for (BlockPos pos : candidates) {
            for (int e = 0; e < eyes.size(); e++) {
                if (canSee(level, eyes.get(e), pos)) {
                    data.markSeen(pos.getY() >> 4,
                            ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
                    break;
                }
            }
        }

        data.ready = true;
        return data;
    }

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
            if (rayClear(level, eye, t)) return true;
        }
        return false;
    }

    /** Amanatides–Woo voxel DDA; true if nothing opaque blocks the segment. */
    static boolean rayClear(ServerLevel level, Vec3 from, Vec3 to) {
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
            BlockState state = level.getBlockStateIfLoaded(mpos);
            if (state == null) return true;   // unloaded chunk: fail open
            if (state.isSolidRender()) return false; // blocked
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Entity occlusion
    // ------------------------------------------------------------------

    /** entityId -> (pairKey -> packed verdict: abs(value)-1 = tick, sign = visible/occluded). */
    private static final Long2ObjectOpenHashMap<Long2LongOpenHashMap> entityVerdicts =
            new Long2ObjectOpenHashMap<>();

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

        long pairKey = (player.getId() & 0xFFFFFFFFL) | ((entity.getId() & 0xFFFFFFFFL) << 32);
        long nowTick = player.level().getGameTime();

        Long2LongOpenHashMap perEntity = entityVerdicts.get(entity.getId());
        if (perEntity != null) {
            long verdict = perEntity.get(pairKey);
            if (verdict != 0 && nowTick - (Math.abs(verdict) - 1) < OsmiumConfig.entityOcclusionCheckIntervalTicks) {
                return verdict < 0;
            }
        }

        // Stale verdict: queue an off-main-thread recompute using our own DDA
        // walker (loaded chunks only, fails open). Until the fresh verdict
        // lands the entity stays VISIBLE — never hide on unknown data.
        scheduleTrace(player, entity, pairKey);
        return false;
    }

    private static void scheduleTrace(ServerPlayer player, Entity entity, long pairKey) {
        if (!pendingTraces.add(pairKey)) return;                   // already queued
        if (pendingTraces.size() > MAX_PENDING_TRACES) {           // overload: fail open
            pendingTraces.remove(pairKey);
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
        final int entityId = entity.getId();
        final long nowTick = level.getGameTime();

        try {
            workers().execute(() -> {
                try {
                    boolean visible = false;
                    for (Vec3 t : targets) {
                        if (rayClear(level, eye, t)) { visible = true; break; }
                    }
                    boolean occluded = !visible;
                    entityVerdicts.computeIfAbsent(entityId, k -> new Long2LongOpenHashMap())
                            .put(pairKey, (occluded ? -1L : 1L) * (nowTick + 1));
                } catch (Exception ignored) {
                    // any failure: no verdict stored -> entity stays visible
                } finally {
                    pendingTraces.remove(pairKey);
                }
            });
        } catch (Exception rejected) {
            pendingTraces.remove(pairKey);
        }
    }

    private static void sweepEntityVerdicts(long nowTick) {
        if (entityVerdicts.isEmpty()) return;
        long ttl = OsmiumConfig.entityOcclusionCheckIntervalTicks * 4L + 40L;
        Iterator<Long2ObjectOpenHashMap.Entry<Long2LongOpenHashMap>> outer =
                entityVerdicts.long2ObjectEntrySet().iterator();
        while (outer.hasNext()) {
            Long2LongOpenHashMap perEntity = outer.next().getValue();
            perEntity.long2LongEntrySet().removeIf(e -> nowTick - (Math.abs(e.getLongValue()) - 1) > ttl);
            if (perEntity.isEmpty()) outer.remove();
        }
    }

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Occlusion");
}
