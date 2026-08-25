package org.osmium.anticheat;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
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
    /** Chunks whose first visibility data just landed — need a resend to reveal. */
    private static final ConcurrentLinkedQueue<ChunkJob> newlyReady = new ConcurrentLinkedQueue<>();
    /** Chunks queued but not yet handed to a worker (dedup for enqueue). */
    private static final Set<Long> QUEUED = ConcurrentHashMap.newKeySet();
    /** Chunks currently being computed by a worker. */
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
        QUEUED.clear();
        inFlight.clear();
        newlyReady.clear();
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
        // Inverted (RayTraceAntiXray-style) semantics: unknown = HIDDEN.
        // The packet ships the replacement block until a visibility
        // computation confirms the block is exposed to a player; the next
        // resend of this chunk then carries the real block. Never leaks.
        if (data == null || !data.ready) {
            debugLog("[antixray] lookup chunk " + (int) chunkKey + "," + (int) (chunkKey >> 32)
                    + ": " + (data == null ? "NO DATA" : "NOT READY") + " -> hidden");
            return true;
        }
        return !data.isSeen(sectionY, packedBlockIndex);
    }

    private static int countSeen(VisibilityData data) {
        int n = 0;
        for (long bits : data.seen) {
            if (bits != 0) n += Long.bitCount(bits);
        }
        return n;
    }

    /** Debug logging — opt-in via raytrace-hiding.debug, unthrottled. */
    private static void debugLog(String msg) {
        if (OsmiumConfig.raytraceDebug) LOGGER.info(msg);
    }

    /** Public entry for debug logs from the chunk processor. */
    public static void debugLogPublic(String msg) {
        debugLog(msg);
    }

    /** Marks a chunk dirty for recomputation. Any thread. */
    public static void invalidateChunk(ServerLevel level, long chunkKey) {
        if (!OsmiumConfig.raytraceHidingEnabled) return;
        cacheRemove(chunkKey);
        enqueue(level, chunkKey);
    }

    /**
     * Packet-time trigger: ensures a chunk being sent has a (re)computation
     * queued. First send computes it; until then lookups fail open.
     */
    public static void ensureComputed(ServerLevel level, long chunkKey) {
        if (!OsmiumConfig.raytraceHidingEnabled) return;
        boolean wasReady;
        synchronized (stripe(chunkKey)) {
            VisibilityData d = visibilityCache.get(chunkKey);
            wasReady = d != null && d.ready;
            if (wasReady) return;
        }
        debugLog("[antixray] chunk " + (int) chunkKey + "," + (int) (chunkKey >> 32)
                + " not ready -> queued (cacheSize=" + visibilityCache.size()
                + " dirty=" + dirtyChunks.size() + " inFlight=" + inFlight.size() + ")");
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
        if (QUEUED.size() > 4096) return; // bounded backlog
        Long boxed = chunkKey;
        if (!QUEUED.add(boxed)) {
            // already queued (or in flight): remember that its result will be
            // stale the moment it lands — the worker re-runs it.
            invalidatedWhileFlying.add(boxed);
            return;
        }
        dirtyChunks.add(new ChunkJob(level, boxed));
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
            resendNewlyReady(server);
        }

        if (entities && nowTick - lastVerdictSweep >= 20) {
            lastVerdictSweep = nowTick;
            sweepEntityVerdicts(nowTick);
        }
    }

    /** Submits up to the per-tick cap worth of jobs to the worker pool. */
    private static void dispatchJobs(MinecraftServer server) {
        int cap = Math.max(1, OsmiumConfig.raytraceChecksPerTick);
        int dispatched = 0;
        ChunkJob job;
        while (dispatched < cap && (job = dirtyChunks.poll()) != null) {
            final ChunkJob readyJob = job;
            long key = job.chunkKey();
            QUEUED.remove(key);
            if (!inFlight.add(key)) continue; // already computing (race)
            dispatched++;
            // Compute every invalidated chunk — chunks beyond ray range of any
            // player legitimately hide everything (eyes list comes back empty),
            // which is exactly the correct output for far chunks.
            final ServerLevel level = job.level();
            workers().execute(() -> {
                long startNanos = System.nanoTime();
                try {
                    VisibilityData data;
                    boolean rerun;
                    do {
                        data = computeVisibility(level, key);
                        rerun = invalidatedWhileFlying.remove(key);
                    } while (rerun);
                    if (data != null) {
                        cachePut(key, data);
                        // First ready data for this chunk: packets already sent
                        // carried hidden (replacement) blocks. Queue a resend so
                        // exposed targets get revealed on the next packet.
                        if (countSeen(data) > 0) {
                            newlyReady.add(readyJob);
                        }
                    }
                    if (OsmiumConfig.raytraceDebug) {
                        debugLog("[antixray] computed chunk " + (int) key + "," + (int) (key >> 32)
                                + " -> " + (data == null ? "NULL (chunk unloaded)" : "ready, seen=" + countSeen(data))
                                + " in " + (System.nanoTime() - startNanos) / 1_000_000 + "ms");
                    }
                } catch (Exception e) {
                    LOGGER.error("[Osmium] occlusion compute failed for chunk " + key
                            + ": " + e.getMessage());
                } finally {
                    inFlight.remove(key);
                    // A last-moment invalidation may have raced past; requeue once.
                    if (invalidatedWhileFlying.remove(key)) {
                        enqueue(level, key);
                    }
                }

            });
        }
    }

    /**
     * Resends chunks whose visibility data just became ready so blocks that
     * were shipped hidden get revealed to players actually tracking them.
     * Bounded per tick; main thread only (world access).
     */
    private static void resendNewlyReady(MinecraftServer server) {
        int budget = 16;
        ChunkJob job;
        while (budget-- > 0 && (job = newlyReady.poll()) != null) {
            ServerLevel level = job.level();
            LevelChunk chunk = level.getChunkSource().getChunkNow(
                    (int) job.chunkKey(), (int) (job.chunkKey() >> 32));
            if (chunk == null) continue;
            for (ServerPlayer p : level.players()) {
                if (p.getChunkTrackingView().contains(chunk.getPos())) {
                    net.minecraft.server.network.PlayerChunkSender.sendChunk(
                            p.connection, level, chunk);
                }
            }
        }
    }

    /** Periodic refresh: re-enqueue live caches, evict dead ones. Bounded work. */
    private static void refreshCycle(MinecraftServer server) {
        List<Long> keys = new ArrayList<>();
        // Snapshot under the SAME stripe locks workers mutate through —
        // a foreign monitor here let iteration race with cachePut and
        // corrupted fastutil's internal arrays (server crash).
        for (Object lock : STRIPE_LOCKS) {
            synchronized (lock) {
                keys.addAll(visibilityCache.keySet());
            }
        }
        int enqueued = 0;
        for (long key : keys) {
            if (enqueued >= 256) break;
            for (ServerLevel lvl : server.getAllLevels()) {
                if (lvl.getChunkSource().getChunkNow((int) key, (int) (key >> 32)) != null) {
                    enqueue(lvl, key);
                    enqueued++;
                    break;
                }
            }
        }
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

    /**
     * Block-entity variant of entity occlusion: chests, furnaces, item frames'
     * holders etc. rendered from chunk data get stripped from the packet when
     * terrain blocks all lines of sight to the receiving player.
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

        return !canSee(level, eye, pos);
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

        // Keyed by UUIDs: entity int ids are recycled after despawn, which
        // could hand a stale verdict to an unrelated new entity.
        long entityKey = entity.getUUID().getMostSignificantBits() ^ entity.getUUID().getLeastSignificantBits();
        long pairKey = player.getUUID().getMostSignificantBits() ^ player.getUUID().getLeastSignificantBits();
        long nowTick = player.level().getGameTime();

        Long2LongOpenHashMap perEntity = entityVerdicts.get(entityKey);
        long previous = 0;
        if (perEntity != null) {
            previous = perEntity.get(pairKey);
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
                    entityVerdicts.computeIfAbsent(entityKey, k -> new Long2LongOpenHashMap())
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
