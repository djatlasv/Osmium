package org.osmium.anticheat;

import io.papermc.paper.antixray.ChunkPacketBlockController;
import io.papermc.paper.antixray.ChunkPacketInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

public class OsmiumChunkProcessor extends ChunkPacketBlockController {

    private final ChunkPacketBlockController delegate;
    private final boolean enabled;
    private final boolean antiXrayActive;   // delegate does its own per-packet rewrite: sharing disabled
    private final int hideBelow;
    private final int proximityRadius;
    private final BlockState replacementState;
    private final int replacementGlobalId;
    private final int replacementVarIntLen;
    private final BlockState[] fallbackStates;

    public OsmiumChunkProcessor(ChunkPacketBlockController delegate, Level level) {
        this.delegate = delegate;
        LIVE_PROCESSORS.add(this); // config-reload cache drops
        // Spawn dimension exclusion: the hub should render fully — no fake
        // deepslate, no stripped block entities. Processor is per-Level so
        // folding this into `enabled` gates every downstream layer.
        boolean spawnDimExcluded = org.osmium.OsmiumConfig.chunkHidingDisableInSpawnDim
                && org.osmium.OsmiumSpawnDim.isSpawnDimension(level);
        this.enabled = org.osmium.OsmiumConfig.chunkHidingEnabled && !spawnDimExcluded;
        this.antiXrayActive = delegate instanceof io.papermc.paper.antixray.ChunkPacketBlockControllerAntiXray;
        this.hideBelow = org.osmium.OsmiumConfig.chunkHidingYThreshold;
        this.proximityRadius = org.osmium.OsmiumConfig.chunkHidingProximityRadius;

        String blockName = org.osmium.OsmiumConfig.chunkHidingBlock;
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(blockName));
        if (block == null) {
            block = Blocks.DEEPSLATE;
            org.bukkit.Bukkit.getLogger().warning("[Osmium] Unknown block '" + blockName + "' in chunk-hiding.block, falling back to deepslate");
        }
        this.replacementState = block.defaultBlockState();
        this.replacementGlobalId = Block.BLOCK_STATE_REGISTRY.getId(this.replacementState);
        this.replacementVarIntLen = varIntLen(this.replacementGlobalId);

        this.fallbackStates = new BlockState[] {
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),
            Blocks.SMOOTH_BASALT.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
            Blocks.CALCITE.defaultBlockState(),
        };
    }

    /**
     * Blocks that cheat stash-finders treat as player-placed anomalies when
     * found below the surface (cobbled/polished deepslate variants are
     * explicit base-detection signals; amethyst family trips geode scanners).
     * Never selected as replacement while any natural alternative exists.
     */
    private static final java.util.Set<Block> SUSPECT_REPLACEMENTS = java.util.Set.of(
            Blocks.COBBLED_DEEPSLATE,
            Blocks.POLISHED_DEEPSLATE,
            Blocks.DEEPSLATE_BRICKS,
            Blocks.DEEPSLATE_TILES,
            Blocks.CHISELED_DEEPSLATE,
            Blocks.COBBLESTONE,
            Blocks.MOSSY_COBBLESTONE,
            Blocks.AMETHYST_BLOCK,
            Blocks.BUDDING_AMETHYST
    );

    /**
     * True if the given position lies in a chunk whose packet this player
     * receives UNREWRITTEN (inside the XZ proximity reveal radius).
     */
    public static boolean withinProximityReveal(ServerPlayer player, BlockPos pos) {
        int px = player.blockPosition().getX();
        int pz = player.blockPosition().getZ();
        int chunkMinX = (pos.getX() >> 4) << 4;
        int chunkMinZ = (pos.getZ() >> 4) << 4;
        int nearestX = Math.max(chunkMinX, Math.min(px, chunkMinX + 15));
        int nearestZ = Math.max(chunkMinZ, Math.min(pz, chunkMinZ + 15));
        int dx = px - nearestX;
        int dz = pz - nearestZ;
        int r = org.osmium.OsmiumConfig.chunkHidingProximityRadius;
        return dx * dx + dz * dz <= r * r;
    }

    @Override
    public boolean shouldModify(ServerPlayer player, LevelChunk chunk) {
        OsmiumChunkPacketInfo.CURRENT_PLAYER.set(player);
        return enabled || delegate.shouldModify(player, chunk);
    }

    @Override
    public ChunkPacketInfo<BlockState> getChunkPacketInfo(LevelChunk chunk) {
        ServerPlayer player = OsmiumChunkPacketInfo.CURRENT_PLAYER.get();
        OsmiumChunkPacketInfo.CURRENT_PLAYER.remove();
        if (!enabled || player == null) {            return delegate.getChunkPacketInfo(chunk);
        }
        OsmiumChunkPacketInfo osmiumInfo = new OsmiumChunkPacketInfo(chunk, player);
        ChunkPacketInfo<BlockState> delegateInfo = delegate.getChunkPacketInfo(chunk);
        osmiumInfo.setDelegateInfo(delegateInfo);
        return osmiumInfo;
    }

    @Override
    public void modifyBlocks(ClientboundLevelChunkWithLightPacket chunkPacket,
                             ChunkPacketInfo<BlockState> chunkPacketInfo) {
        if (chunkPacketInfo instanceof OsmiumChunkPacketInfo osmiumInfo) {
            ChunkPacketInfo<BlockState> delegateInfo = osmiumInfo.getDelegateInfo();
            if (delegateInfo != null) {
                // The chunk serializer populates ONLY the info object the
                // packet constructor handed it — ours. The delegate's
                // ChunkPacketInfoAntiXray would otherwise stay empty
                // (isWritten false everywhere) and its async obfuscator
                // silently skips every section: the exact "RTAX starved by
                // the wrapper" collision. Mirror the captured per-section
                // data into the delegate info (same palette objects and the
                // same buffer reference — the delegate rewrites in place).
                syncDelegateInfo(osmiumInfo, delegateInfo);
            }

            // Paper EM1/RTAX obfuscate ASYNCHRONOUSLY (their worker rewrites
            // the buffer after modifyBlocks returns, right before the packet
            // flushes — the connection's flushQueue gates on isReady). We
            // register the packet and let the setReady hook apply our block
            // layers AFTER the delegate's final write, still before the
            // flush. Light data is never touched by the delegate -> stays
            // synchronous.
            if (antiXrayActive) {
                delegate.modifyBlocks(chunkPacket, delegateInfo != null ? delegateInfo : chunkPacketInfo);
                if (EM1_PENDING.size() > 2048) EM1_PENDING.clear();
                EM1_PENDING.put(chunkPacket, new Em1Pending(this, osmiumInfo));
                applyLightHiding(chunkPacket, osmiumInfo);
                return;
            }

            // Vanilla no-op delegate: run our own block passes ASYNCHRONOUSLY
            // on the block-pass pool, then publish with setReady(true). The
            // connection's FIFO flushQueue holds the packet until ready, so
            // ordering is preserved while the main thread stays free — this
            // is what keeps login bursts (dozens of packets in one tick)
            // from freezing the server. The base delegate's modifyBlocks is
            // deliberately NOT called: it would publish the raw buffer
            // immediately and race our worker's rewrite.
            if (delegate.getClass() == io.papermc.paper.antixray.ChunkPacketBlockController.class) {
                LevelChunk shChunk = osmiumInfo.getChunk();
                long shKey = ((long) shChunk.getPos().x() & 0xFFFFFFFFL)
                        | (((long) shChunk.getPos().z() & 0xFFFFFFFFL) << 32);
                byte[] sharedBuffer = osmiumInfo.getBuffer();
                boolean shareable = org.osmium.OsmiumConfig.chunkHidingSharedRewrites
                        && !isNearViewer(osmiumInfo)
                        && sharedBuffer != null;
                if (shareable) {
                    SharedRewrite shared = SHARED_REWRITES.get(shKey);
                    if (shared != null && System.currentTimeMillis() - shared.atMillis() <= SHARED_TTL_MS
                            && shared.data().length == sharedBuffer.length) {
                        // Cheap path: copy + candidate registration stay on
                        // the calling thread; publish immediately.
                        System.arraycopy(shared.data(), 0, sharedBuffer, 0, sharedBuffer.length);
                        org.osmium.anticheat.OsmiumOcclusion.registerCandidates(
                                osmiumInfo.getPlayer(), shChunk,
                                selectCandidates(shared.candidates(), osmiumInfo.getPlayer()));
                        applyLightHiding(chunkPacket, osmiumInfo);
                        chunkPacket.setReady(true);
                        return;
                    }
                }
                applyLightHiding(chunkPacket, osmiumInfo);
                final List<BlockPos> sharedCandidates = shareable ? new ArrayList<>() : null;
                BLOCK_PASS_EXECUTOR.execute(() -> {
                    try {
                        applyHiding(chunkPacket, osmiumInfo);
                        applyRaytraceHiding(chunkPacket, osmiumInfo, sharedCandidates);
                        if (shareable) {
                            if (SHARED_REWRITES.size() > 64) SHARED_REWRITES.clear(); // buffer clones are ~MB: keep the cap tight
                            long now = System.currentTimeMillis();
                            SHARED_REWRITES.values().removeIf(e ->
                                    now - e.atMillis() > SHARED_TTL_MS);
                            SHARED_REWRITES.put(shKey, new SharedRewrite(
                                    osmiumInfo.getBuffer().clone(),
                                    List.copyOf(sharedCandidates), now));
                        }
                    } catch (Exception e) {
                        org.bukkit.Bukkit.getLogger().warning("[Osmium] async block pass failed: " + e);
                    } finally {
                        // Publish: the FIFO flushQueue only forwards this
                        // packet once ready=true — our buffer writes are
                        // complete before this line (finally ordering).
                        chunkPacket.setReady(true);
                    }
                });
                return;
            }

            // Foreign non-antixray delegate (unknown side effects): keep the
            // legacy synchronous composition.
            delegate.modifyBlocks(chunkPacket, delegateInfo != null ? delegateInfo : chunkPacketInfo);
            if (shareableSync(osmiumInfo)) {
                SharedRewrite shared = SHARED_REWRITES.get(chunkKeyOf(osmiumInfo));
                if (shared != null && System.currentTimeMillis() - shared.atMillis() <= SHARED_TTL_MS
                        && shared.data().length == osmiumInfo.getBuffer().length) {
                    System.arraycopy(shared.data(), 0, osmiumInfo.getBuffer(), 0,
                            osmiumInfo.getBuffer().length);
                    org.osmium.anticheat.OsmiumOcclusion.registerCandidates(
                            osmiumInfo.getPlayer(), osmiumInfo.getChunk(),
                            selectCandidates(shared.candidates(), osmiumInfo.getPlayer()));
                    applyLightHiding(chunkPacket, osmiumInfo);
                    return;
                }
            }
            List<BlockPos> syncCandidates = shareableSync(osmiumInfo) ? new ArrayList<>() : null;
            applyHiding(chunkPacket, osmiumInfo);
            applyRaytraceHiding(chunkPacket, osmiumInfo, syncCandidates);
            if (syncCandidates != null) {
                if (SHARED_REWRITES.size() > 64) SHARED_REWRITES.clear();
                long now = System.currentTimeMillis();
                SHARED_REWRITES.values().removeIf(e -> now - e.atMillis() > SHARED_TTL_MS);
                SHARED_REWRITES.put(chunkKeyOf(osmiumInfo), new SharedRewrite(
                        osmiumInfo.getBuffer().clone(), List.copyOf(syncCandidates), now));
            }
            applyLightHiding(chunkPacket, osmiumInfo);
        } else {
            delegate.modifyBlocks(chunkPacket, chunkPacketInfo);
        }
    }

    private boolean shareableSync(OsmiumChunkPacketInfo osmiumInfo) {
        return org.osmium.OsmiumConfig.chunkHidingSharedRewrites && !isNearViewer(osmiumInfo);
    }

    private static long chunkKeyOf(OsmiumChunkPacketInfo osmiumInfo) {
        return ((long) osmiumInfo.getChunk().getPos().x() & 0xFFFFFFFFL)
                | (((long) osmiumInfo.getChunk().getPos().z() & 0xFFFFFFFFL) << 32);
    }

    /** Dedicated pool for packet-time block passes (kept off the trace pool to avoid head-of-line starvation). */
    private static final java.util.concurrent.ExecutorService BLOCK_PASS_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "Osmium-BlockPass");
                t.setDaemon(true);
                return t;
            });

    /**
     * Copies the serializer-captured section data from our info into the
     * delegate's ChunkPacketInfoAntiXray: per-section bit widths, palette
     * references, buffer offsets, preset values, and the shared byte buffer.
     * isWritten() derives from bits != 0, so unwritten sections stay
     * unwritten for the delegate too.
     */
    private static void syncDelegateInfo(OsmiumChunkPacketInfo from, ChunkPacketInfo<BlockState> to) {
        byte[] buffer = from.getBuffer();
        if (buffer != null) to.setBuffer(buffer);
        int sections = from.getChunk().getSectionsCount();
        for (int i = 0; i < sections; i++) {
            if (!from.isWritten(i)) continue;
            to.setBits(i, from.getBits(i));
            to.setPalette(i, from.getPalette(i));
            to.setIndex(i, from.getIndex(i));
            to.setPresetValues(i, from.getPresetValues(i));
        }
    }

    // --- Per-section palette classification cache (Stage 2) ---
    // One valueFor walk + one findInPalette scan per (chunk, section,
    // palette identity) instead of per packet per viewer. Slots validate
    // against the live palette object AND its size: an edited chunk swaps
    // or grows the palette, both of which miss and recompute. Explicitly
    // dropped from onBlockChange as well. GATED OFF when an anti-xray
    // delegate is active: EM1/RTAX append palette ids in place at packet
    // time, which identity checks cannot see.

    private record SectionClassif(BlockState[] states, boolean[] solid, boolean[] target,
                                  boolean anyTarget, int replacementId) {}

    private record CacheSlot(Palette<BlockState> palette, SectionClassif classif) {}

    // Per-processor (per-Level!) maps: chunk (x,z) keys collide across
    // dimensions, so both caches must never be shared between levels.
    /** chunkKey -> (sectionIndex -> slot) */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<Integer, CacheSlot>> PALETTE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** chunkKey -> SharedRewrite (per level) */
    private final java.util.concurrent.ConcurrentHashMap<Long, SharedRewrite> SHARED_REWRITES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** All live processors, for config-reload cache drops. */
    private static final java.util.Set<OsmiumChunkProcessor> LIVE_PROCESSORS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Config-reload hook (called via OsmiumOcclusion.clearCaches). */
    static void clearPaletteCache() {
        for (OsmiumChunkProcessor p : LIVE_PROCESSORS) {
            p.PALETTE_CACHE.clear();
            p.SHARED_REWRITES.clear();
        }
    }

    private SectionClassif classify(long chunkKey, int sectionIndex, Palette<BlockState> palette) {
        if (antiXrayActive) return computeClassif(palette);
        if (PALETTE_CACHE.size() > 4096) PALETTE_CACHE.clear();
        var perChunk = PALETTE_CACHE.computeIfAbsent(chunkKey, k -> new java.util.concurrent.ConcurrentHashMap<>());
        CacheSlot slot = perChunk.get(sectionIndex);
        if (slot != null && slot.palette() == palette
                && slot.classif().states().length == palette.getSize()) {
            return slot.classif();
        }
        SectionClassif fresh = computeClassif(palette);
        perChunk.put(sectionIndex, new CacheSlot(palette, fresh));
        return fresh;
    }

    private SectionClassif computeClassif(Palette<BlockState> palette) {
        int size = palette.getSize();
        BlockState[] states = new BlockState[size];
        boolean[] solid = new boolean[size];
        boolean[] target = new boolean[size];
        boolean anyTarget = false;
        Set<Block> targets = org.osmium.anticheat.OsmiumOcclusion.targetBlocks();
        for (int i = 0; i < size; i++) {
            BlockState st = null;
            try { st = palette.valueFor(i); } catch (Exception ignored) {}
            states[i] = st;
            if (st != null) {
                solid[i] = st.isSolidRender();
                target[i] = targets.contains(st.getBlock());
                anyTarget |= target[i];
            }
        }
        return new SectionClassif(states, solid, target, anyTarget, findInPalette(states));
    }

    /**
     * Finds the best replacement block in the palette WITHOUT adding it.
     * Priority: configured block > deepslate/stone/tuff/etc > any solid block.
     * Returns -1 only if no suitable replacement exists.
     */
    private int findInPalette(BlockState[] states) {
        int size = states.length;

        // Priority 1: exact configured block
        for (int i = 0; i < size; i++) {
            if (replacementState.equals(states[i])) return i;
        }

        // Priority 2: known natural stone-family fallbacks, in declared order
        // (declared list is already free of player-associated variants)
        for (BlockState fallback : fallbackStates) {
            if (fallback.equals(replacementState)) continue;
            for (int i = 0; i < size; i++) {
                if (fallback.equals(states[i])) return i;
            }
        }

        // Priority 3: any solid opaque non-fluid NATURAL block — skips
        // player-associated variants stash-finders flag as anomalies.
        for (int i = 0; i < size; i++) {
            BlockState state = states[i];
            if (state != null && !state.isAir()
                    && state.getFluidState().is(Fluids.EMPTY)
                    && !SUSPECT_REPLACEMENTS.contains(state.getBlock())
                    && state.isSolidRender()) {
                return i;
            }
        }

        // Priority 4 (absolute last resort): ANY non-air block, even
        // suspicious ones — better a faint statistical anomaly than a hole.
        for (int i = 0; i < size; i++) {
            if (states[i] != null && !states[i].isAir()) return i;
        }

        return -1;
    }

    private void applyHiding(ClientboundLevelChunkWithLightPacket chunkPacket,
                              OsmiumChunkPacketInfo chunkPacketInfo) {
        LevelChunk chunk = chunkPacketInfo.getChunk();
        int minSectionY = chunk.getMinSectionY();
        int hideBelowSection = hideBelow >> 4;

        byte[] buffer = chunkPacketInfo.getBuffer();
        if (buffer == null) return;

        ServerPlayer player = chunkPacketInfo.getPlayer();
        int playerBlockX = player.blockPosition().getX();
        int playerBlockY = player.blockPosition().getY();
        int playerBlockZ = player.blockPosition().getZ();

        int chunkBlockX = chunk.getPos().x() << 4;
        int chunkBlockZ = chunk.getPos().z() << 4;

        int nearestX = Math.max(chunkBlockX, Math.min(playerBlockX, chunkBlockX + 15));
        int nearestZ = Math.max(chunkBlockZ, Math.min(playerBlockZ, chunkBlockZ + 15));
        int xzDistSq = (playerBlockX - nearestX) * (playerBlockX - nearestX)
                      + (playerBlockZ - nearestZ) * (playerBlockZ - nearestZ);
        int proxSq = proximityRadius * proximityRadius;
        boolean xzNear = xzDistSq <= proxSq;

        long chunkKey = ((long) chunk.getPos().x() & 0xFFFFFFFFL)
                | (((long) chunk.getPos().z() & 0xFFFFFFFFL) << 32);

        io.papermc.paper.antixray.BitStorageReader reader = new io.papermc.paper.antixray.BitStorageReader();
        io.papermc.paper.antixray.BitStorageWriter writer = new io.papermc.paper.antixray.BitStorageWriter();
        reader.setBuffer(buffer);
        writer.setBuffer(buffer);

        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            int sectionY = sectionIndex + minSectionY;
            if (sectionY >= hideBelowSection) continue;

            // Proximity reveal: horizontal distance only — the hidden zone is
            // always BELOW surface players, so vertical offset would make
            // reveal impossible at realistic radii.
            if (xzNear) continue;

            Palette<BlockState> palette = chunkPacketInfo.getPalette(sectionIndex);
            if (palette == null || palette.getSize() < 1) continue;

            // Single-value sections (palette size 1, any bit width): the whole
            // section is one block stored as a single palette VarInt + uniform
            // data array. Replace the palette entry in-place when the VarInt
            // byte lengths match — skips a full 4096-entry rewrite.
            if (palette.getSize() == 1) {
                BlockState currentState;
                try { currentState = palette.valueFor(0); } catch (Exception e) { continue; }
                if (currentState == null) continue;

                int currentGlobalId = Block.BLOCK_STATE_REGISTRY.getId(currentState);
                if (currentGlobalId == replacementGlobalId) continue; // already the right block

                int currentLen = varIntLen(currentGlobalId);

                // Length-mismatch fallback: a different-length VarInt would
                // shift every later byte in the buffer, so we can't swap
                // freely. Instead pick ANY natural stone-family block whose
                // global id happens to encode to the SAME length — keeps the
                // section rewritten (no water/air leaks below the threshold)
                // without rebuilding the packet.
                int chosenGlobalId = -1;
                if (currentLen == replacementVarIntLen) {
                    chosenGlobalId = replacementGlobalId;
                } else {
                    for (BlockState fallback : fallbackStates) {
                        if (fallback.equals(replacementState)) continue;
                        int gid = Block.BLOCK_STATE_REGISTRY.getId(fallback);
                        if (gid >= 0 && varIntLen(gid) == currentLen) {
                            chosenGlobalId = gid;
                            break;
                        }
                    }
                }

                if (chosenGlobalId >= 0) {
                    int dataArrayIndex = chunkPacketInfo.getIndex(sectionIndex);
                    writeVarInt(buffer, dataArrayIndex - currentLen, chosenGlobalId);
                }
                continue;
            }

            int bits = chunkPacketInfo.getBits(sectionIndex);

            if (!chunkPacketInfo.isWritten(sectionIndex)) continue;

            int replacementPaletteId = palette instanceof GlobalPalette
                    ? replacementGlobalId
                    : classify(chunkKey, sectionIndex, palette).replacementId();
            if (replacementPaletteId < 0) continue;

            int index = chunkPacketInfo.getIndex(sectionIndex);
            reader.setBits(bits);
            reader.setIndex(index);
            writer.setBits(bits);
            writer.setIndex(index);

            for (int i = 0; i < 4096; i++) {
                reader.read();
                writer.write(replacementPaletteId);
            }

            writer.flush();
        }
    }

    // --- Shared far-view rewrites ---
    private static final long SHARED_TTL_MS = 30_000;
    private record SharedRewrite(byte[] data, List<BlockPos> candidates, long atMillis) {}

    /** True when the viewer is inside the XZ proximity-reveal radius of this chunk. */
    private boolean isNearViewer(OsmiumChunkPacketInfo info) {
        LevelChunk chunk = info.getChunk();
        ServerPlayer player = info.getPlayer();
        int playerX = player.blockPosition().getX();
        int playerZ = player.blockPosition().getZ();
        int chunkBlockX = chunk.getPos().x() << 4;
        int chunkBlockZ = chunk.getPos().z() << 4;
        int nearestX = Math.max(chunkBlockX, Math.min(playerX, chunkBlockX + 15));
        int nearestZ = Math.max(chunkBlockZ, Math.min(playerZ, chunkBlockZ + 15));
        int dx = playerX - nearestX;
        int dz = playerZ - nearestZ;
        int proxSq = proximityRadius * proximityRadius;
        return dx * dx + dz * dz <= proxSq;
    }

    /**
     * Near-first candidate selection: the viewer's closest exposed targets
     * get the trace cap. Applied per viewer — on shared-cache hits the
     * cached (unsorted) exposed list is re-sorted for the receiving player.
     */
    private static List<BlockPos> selectCandidates(List<BlockPos> exposed, ServerPlayer owner) {
        if (exposed.size() <= MAX_RAYTRACE_CANDIDATES) return exposed;
        Vec3 eye = owner.getEyePosition();
        double ex = eye.x, ey = eye.y, ez = eye.z;
        List<BlockPos> sorted = new ArrayList<>(exposed);
        sorted.sort(java.util.Comparator.comparingDouble((BlockPos p) -> {
            double dx = p.getX() + 0.5 - ex;
            double dy = p.getY() + 0.5 - ey;
            double dz = p.getZ() + 0.5 - ez;
            return dx * dx + dy * dy + dz * dz;
        }));
        while (sorted.size() > MAX_RAYTRACE_CANDIDATES) {
            sorted.remove(sorted.size() - 1);
        }
        return sorted;
    }

    // --- Post-antixray composition (EM1 async pipeline) ---

    private record Em1Pending(OsmiumChunkProcessor processor, OsmiumChunkPacketInfo info) {}
    /** packet -> pending post-EM1 fill. Entries live only until the packet flushes. */
    private static final java.util.concurrent.ConcurrentHashMap<
            ClientboundLevelChunkWithLightPacket, Em1Pending> EM1_PENDING =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Re-assert our wrapping when a plugin reflectively replaces the level
     * controller AFTER our constructor ran. RayTraceAntiXray does exactly
     * that: it swaps chunkPacketBlockController to its own AntiXray
     * implementation (final-field mutation), discarding our wrapper and
     * with it all native layers. Re-wrap with the newcomer as delegate so
     * composition is restored: RTAX obfuscates -> setReady hook -> our
     * blanket fill -> flush.
     */
    public static void ensureWrapped(net.minecraft.server.MinecraftServer server) {
        for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
            io.papermc.paper.antixray.ChunkPacketBlockController cur = lvl.chunkPacketBlockController;
            if (cur instanceof OsmiumChunkProcessor) continue;
            try {
                java.lang.reflect.Field f = net.minecraft.world.level.Level.class.getDeclaredField("chunkPacketBlockController");
                f.setAccessible(true);
                f.set(lvl, new OsmiumChunkProcessor(cur, lvl));
                org.bukkit.Bukkit.getLogger().warning("[Osmium] re-wrapped level controller for "
                        + lvl.dimension().identifier() + " (delegate=" + cur.getClass().getName() + ")");
            } catch (Exception e) {
                org.bukkit.Bukkit.getLogger().warning("[Osmium] failed to re-wrap level controller: " + e);
            }
        }
    }

    /**
     * Called from ClientboundLevelChunkWithLightPacket#setReady(true) — i.e.
     * by the EM1 worker the moment its obfuscation pass finished, BEFORE the
     * ready flag publishes the buffer to the connection thread. This is the
     * only safe point to apply our block hiding on top of EM1 output.
     * Light hiding already ran synchronously in modifyBlocks; BE stripping is
     * handled at ChunkMap level.
     */
    public static void onPacketReady(ClientboundLevelChunkWithLightPacket packet) {
        try {
            Em1Pending pending = EM1_PENDING.remove(packet);
            if (pending == null) return;
            // Run the block passes for the anti-Xray path too. EM1's async
            // rewrite preserves the section layout our captured indices point
            // at, so both passes are safe here — and section ownership in
            // applyRaytraceHiding keeps it from re-processing blanket-owned
            // sections.
            pending.processor().applyHiding(packet, pending.info());
            pending.processor().applyRaytraceHiding(packet, pending.info(), null);
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().warning("[Osmium] post-antixray fill failed: " + e);
        }
    }

/**
     * Raytrace-based selective ore hiding — RTAX model (adapted from
     * github.com/stonar96/RayTraceAntiXray, MIT). Unlike applyHiding (which
     * rewrites every entry below the threshold), this replaces ONLY target
     * blocks in RTAX-owned sections (near or above-threshold — see section
     * ownership), registering the air-exposed ones as per-player candidates.
     * The occlusion engine traces candidates and reveals visible blocks with
     * per-block update packets; buried targets stay hidden permanently.
     *
     * Stage 2: palette classification comes from the per-section cache (see
     * classify) — sections without targets skip the 4096-entry read
     * entirely. When candidatesOut is non-null the raw exposed list is
     * collected for the shared-far-view cache instead of registering.
     */
    private void applyRaytraceHiding(ClientboundLevelChunkWithLightPacket chunkPacket,
                                     OsmiumChunkPacketInfo info,
                                     List<BlockPos> candidatesOut) {
        if (!org.osmium.OsmiumConfig.raytraceHidingEnabled) return;

        Set<Block> targets = org.osmium.anticheat.OsmiumOcclusion.targetBlocks();
        if (targets.isEmpty()) return;

        LevelChunk chunk = info.getChunk();
        boolean debug = org.osmium.OsmiumConfig.raytraceDebug;

        // Section ownership (see applyHiding): the blanket pass owns far
        // below-threshold sections — it already rewrote them to the
        // replacement block, so the original target IDs RTAX matches against
        // no longer exist there. RTAX only owns near sections and sections at
        // or above the threshold, where the original data is intact.
        ServerPlayer owner = info.getPlayer();
        int ownerX = owner.blockPosition().getX();
        int ownerZ = owner.blockPosition().getZ();
        int chunkBlockX = chunk.getPos().x() << 4;
        int chunkBlockZ = chunk.getPos().z() << 4;
        int nearestX = Math.max(chunkBlockX, Math.min(ownerX, chunkBlockX + 15));
        int nearestZ = Math.max(chunkBlockZ, Math.min(ownerZ, chunkBlockZ + 15));
        int ownDx = ownerX - nearestX;
        int ownDz = ownerZ - nearestZ;
        int proxSqOwn = proximityRadius * proximityRadius;
        boolean ownerNear = ownDx * ownDx + ownDz * ownDz <= proxSqOwn;
        int hideBelowSectionOwn = hideBelow >> 4;

        byte[] buffer = info.getBuffer();
        if (buffer == null) {
            if (debug) org.osmium.anticheat.OsmiumOcclusion.debugLogPublic(
                    "[antixray] chunk " + chunk.getPos().x() + "," + chunk.getPos().z()
                            + ": buffer null -> nothing to rewrite");
            return;
        }

        int minSectionY = chunk.getMinSectionY();
        int sectionsCount = chunk.getSectionsCount();
        long chunkKey = ((long) chunk.getPos().x() & 0xFFFFFFFFL)
                | (((long) chunk.getPos().z() & 0xFFFFFFFFL) << 32);

        // ThreadLocal scratch: [0..4095] current section ids, [4096..8191]
        // below-adjacent ids, [8192..12287] above-adjacent ids (both needed
        // when a section has edge-row targets at both ends). Avoids
        // per-packet allocations.
        int[] scratch = SCRATCH_IDS.get();

        List<BlockPos> candidates = candidatesOut != null ? candidatesOut : new ArrayList<>();
        io.papermc.paper.antixray.BitStorageReader reader = new io.papermc.paper.antixray.BitStorageReader();
        io.papermc.paper.antixray.BitStorageWriter writer = new io.papermc.paper.antixray.BitStorageWriter();
        reader.setBuffer(buffer);
        writer.setBuffer(buffer);

        int replaced = 0;
        for (int sectionIndex = 0; sectionIndex < sectionsCount; sectionIndex++) {
            // Section ownership: skip blanket-owned (far + below-threshold)
            // sections — their target blocks were already replaced wholesale.
            int ownSectionY = minSectionY + sectionIndex;
            if (!ownerNear && ownSectionY < hideBelowSectionOwn) continue;

            int bits = info.getBits(sectionIndex);
            if (bits <= 0) { if (debug) skipDebug(chunk, ownSectionY, "bits<=0", null); continue; }
            if (!info.isWritten(sectionIndex)) { if (debug) skipDebug(chunk, ownSectionY, "not-written", null); continue; }
            Palette<BlockState> palette = info.getPalette(sectionIndex);
            // GlobalPalette (registry-id sections) are skipped: blanket owns
            // their hiding path, and per-entry classification there would
            // mean registry lookups for an essentially unreachable case.
            if (palette == null) { if (debug) skipDebug(chunk, ownSectionY, "palette-null", null); continue; }
            if (palette instanceof GlobalPalette) { if (debug) skipDebug(chunk, ownSectionY, "global-palette", palette); continue; }
            if (palette.getSize() < 2) { if (debug) skipDebug(chunk, ownSectionY, "single-entry", palette); continue; }

            // Classification FIRST (cached): sections without any target
            // block never pay for the 4096-entry id read.
            SectionClassif classif = classify(chunkKey, sectionIndex, palette);
            if (!classif.anyTarget()) { if (debug) skipDebug(chunk, ownSectionY, "no-target", palette); continue; }
            if (debug) skipDebug(chunk, ownSectionY, "PROCESS", palette);

            boolean[] solid = classif.solid();
            boolean[] target = classif.target();
            int[] ids = scratch;
            reader.setBits(bits);
            reader.setIndex(info.getIndex(sectionIndex));
            for (int p = 0; p < 4096; p++) ids[p] = reader.read();

            int replacementPaletteId = classif.replacementId();
            if (replacementPaletteId < 0) { if (debug) skipDebug(chunk, ownSectionY, "no-replacement", palette); continue; }

            writer.setBits(bits);
            writer.setIndex(info.getIndex(sectionIndex));

            int yBase = ownSectionY << 4;
            int minX = chunkBlockX;
            int minZ = chunkBlockZ;

            // Adjacent-section ids are only needed for edge rows; read them
            // lazily the first time an edge-row target is checked. A missing
            // (unwritten / out-of-range) neighbor is pre-marked "read" so the
            // null ids fail open in isAirExposed.
            boolean belowRead = sectionIndex - 1 < 0 || !info.isWritten(sectionIndex - 1);
            boolean aboveRead = sectionIndex + 1 >= sectionsCount || !info.isWritten(sectionIndex + 1);
            int[] belowIds = null;
            boolean[] belowSolid = null;
            int[] aboveIds = null;
            boolean[] aboveSolid = null;

            // Palette data is packed y<<8 | z<<4 | x — loop index == packed index
            for (int p = 0; p < 4096; p++) {
                int id = ids[p];
                int out = id;
                if (target[id]) {
                    out = replacementPaletteId;
                    replaced++;
                    if (candidates.size() < MAX_CANDIDATE_COLLECTION) {
                        int px = p & 15;
                        int py = p >> 8;
                        int pz = (p >> 4) & 15;

                        if (py == 0 && !belowRead) {
                            belowRead = true;
                            belowIds = readSectionIds(reader, info, sectionIndex - 1, 4096);
                            Palette<BlockState> belowPalette = belowIds == null ? null : info.getPalette(sectionIndex - 1);
                            belowSolid = belowPalette == null ? null : classify(chunkKey, sectionIndex - 1, belowPalette).solid();
                        } else if (py == 15 && !aboveRead) {
                            aboveRead = true;
                            aboveIds = readSectionIds(reader, info, sectionIndex + 1, 8192);
                            Palette<BlockState> abovePalette = aboveIds == null ? null : info.getPalette(sectionIndex + 1);
                            aboveSolid = abovePalette == null ? null : classify(chunkKey, sectionIndex + 1, abovePalette).solid();
                        }

                        if (isAirExposed(ids, solid, belowIds, belowSolid, aboveIds, aboveSolid, px, py, pz)) {
                            candidates.add(new BlockPos(minX + px, yBase + py, minZ + pz));
                        }
                    }
                }
                writer.write(out);
            }

            writer.flush();
        }

        if (candidatesOut == null) {
            // Near-first candidate selection: the receiver's closest exposed
            // targets get the trace cap (see selectCandidates).
            List<BlockPos> selected = selectCandidates(candidates, owner);
            // Fresh packet = fresh candidate set: replaces any stale entry
            // for this chunk; pending reveals for the old set are discarded
            // by the drain-time freshness check.
            org.osmium.anticheat.OsmiumOcclusion.registerCandidates(owner, chunk, selected);
            candidates = selected;
        }

        if (debug) {
            org.osmium.anticheat.OsmiumOcclusion.debugLogPublic(
                    "[antixray] packet chunk " + chunk.getPos().x() + "," + chunk.getPos().z()
                            + ": replaced=" + replaced + " candidates=" + candidates.size());
        }
    }

    /** Reads one section's palette ids into the scratch half at the given offset. */
    private static void skipDebug(LevelChunk chunk, int sectionY, String reason, Palette<BlockState> palette) {
        StringBuilder sb = new StringBuilder("[antixray] skip ").append(chunk.getPos().x())
                .append(',').append(chunk.getPos().z()).append(" section y=").append(sectionY)
                .append(": ").append(reason);
        if (palette != null) {
            sb.append(" size=").append(palette.getSize()).append(" [");
            for (int i = 0; i < Math.min(palette.getSize(), 24); i++) {
                try {
                    BlockState st = palette.valueFor(i);
                    if (st != null) sb.append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath())
                            .append(i < palette.getSize() - 1 ? ", " : "");
                } catch (Exception ignored) {}
            }
            sb.append(']');
        }
        org.osmium.anticheat.OsmiumOcclusion.debugLogPublic(sb.toString());
    }

    /** Reads one section's palette ids into the scratch half at the given offset. */
    private static int[] readSectionIds(io.papermc.paper.antixray.BitStorageReader reader,
                                        OsmiumChunkPacketInfo info, int sectionIndex, int offset) {
        int bits = info.getBits(sectionIndex);
        if (bits <= 0 || !info.isWritten(sectionIndex)) return null;
        int[] adj = SCRATCH_IDS.get();
        reader.setBits(bits);
        reader.setIndex(info.getIndex(sectionIndex));
        for (int p = 0; p < 4096; p++) adj[offset + p] = reader.read();
        return adj;
    }

    /** RTAX max-ray-trace-block-count-per-chunk (shipped default). */
    private static final int MAX_RAYTRACE_CANDIDATES = 100;

    /** Hard bound on the pre-sort collection list (safety valve for ore-riddled chunks). */
    private static final int MAX_CANDIDATE_COLLECTION = 4096;

    /** [0..4095] = current section, [4096..8191] = below, [8192..12287] = above. */
    private static final ThreadLocal<int[]> SCRATCH_IDS = ThreadLocal.withInitial(() -> new int[12288]);

    /**
     * Air-exposure test on the captured section snapshot: any non-solid
     * 6-neighbor makes the block a ray-trace candidate. Chunk-edge columns
     * fail OPEN (neighbor chunk data is not in this packet); missing
     * adjacent sections (null ids) fail open the same way.
     */
    private static boolean isAirExposed(int[] ids, boolean[] solid,
                                        int[] belowIds, boolean[] belowSolid,
                                        int[] aboveIds, boolean[] aboveSolid,
                                        int px, int py, int pz) {
        if (px == 0 || px == 15 || pz == 0 || pz == 15) return true;
        if (!isSolidAt(ids, solid, px - 1, py, pz)) return true;
        if (!isSolidAt(ids, solid, px + 1, py, pz)) return true;
        if (!isSolidAt(ids, solid, px, py, pz - 1)) return true;
        if (!isSolidAt(ids, solid, px, py, pz + 1)) return true;
        if (py == 0) {
            if (belowIds == null) return true;
            if (!isSolidAt(belowIds, belowSolid, px, 15, pz)) return true;
        } else if (!isSolidAt(ids, solid, px, py - 1, pz)) return true;
        if (py == 15) {
            if (aboveIds == null) return true;
            if (!isSolidAt(aboveIds, aboveSolid, px, 0, pz)) return true;
        } else if (!isSolidAt(ids, solid, px, py + 1, pz)) return true;
        return false;
    }

    private static boolean isSolidAt(int[] ids, boolean[] solid, int px, int py, int pz) {
        if (solid == null) return false; // missing classification: fail open
        int id = ids[(py << 8) | (pz << 4) | px];
        return id < solid.length && solid[id];
    }

    // --- Light hiding (defeats Light Finder hacks) ---

    private void applyLightHiding(ClientboundLevelChunkWithLightPacket chunkPacket,
                                   OsmiumChunkPacketInfo info) {
        if (!org.osmium.OsmiumConfig.chunkHidingHideLight) return;

        ClientboundLightUpdatePacketData lightData = chunkPacket.lightData();
        LevelChunk chunk = info.getChunk();
        int minLightSection = chunk.getMinSectionY() - 1;
        int hideBelowSection = hideBelow >> 4;

        ServerPlayer player = info.getPlayer();
        int playerBlockX = player.blockPosition().getX();
        int playerBlockY = player.blockPosition().getY();
        int playerBlockZ = player.blockPosition().getZ();
        int chunkBlockX = chunk.getPos().x() << 4;
        int chunkBlockZ = chunk.getPos().z() << 4;
        int nearestX = Math.max(chunkBlockX, Math.min(playerBlockX, chunkBlockX + 15));
        int nearestZ = Math.max(chunkBlockZ, Math.min(playerBlockZ, chunkBlockZ + 15));
        int xzDistSq = (playerBlockX - nearestX) * (playerBlockX - nearestX)
                      + (playerBlockZ - nearestZ) * (playerBlockZ - nearestZ);
        int proxSq = proximityRadius * proximityRadius;
        boolean xzNear = xzDistSq <= proxSq;

        zeroHiddenLightSections(lightData.skyYMask(), lightData.skyUpdates(),
                minLightSection, hideBelowSection, xzNear, playerBlockY, xzDistSq, proxSq);
        zeroHiddenLightSections(lightData.blockYMask(), lightData.blockUpdates(),
                minLightSection, hideBelowSection, xzNear, playerBlockY, xzDistSq, proxSq);
    }

    private void zeroHiddenLightSections(BitSet mask, List<byte[]> updates,
                                          int minLightSection, int hideBelowSection,
                                          boolean xzNear, int playerBlockY,
                                          int xzDistSq, int proxSq) {
        int listIndex = 0;
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) {
            int sectionY = minLightSection + i;
            if (sectionY < hideBelowSection) {
                boolean reveal = false;
                if (xzNear) {
                    int sectionMinY = sectionY << 4;
                    int sectionMaxY = sectionMinY + 15;
                    int yDist = playerBlockY < sectionMinY ? sectionMinY - playerBlockY
                              : playerBlockY > sectionMaxY ? playerBlockY - sectionMaxY
                              : 0;
                    if (yDist * yDist + xzDistSq <= proxSq) reveal = true;
                }
                if (!reveal) {
                    Arrays.fill(updates.get(listIndex), (byte) 0);
                }
            }
            listIndex++;
        }
    }

    // --- VarInt helpers for bits=0 palette replacement ---

    private static int varIntLen(int value) {
        int len = 0;
        do {
            len++;
            value >>>= 7;
        } while (value != 0);
        return len;
    }

    private static void writeVarInt(byte[] buffer, int offset, int value) {
        while ((value & ~0x7F) != 0) {
            buffer[offset++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buffer[offset] = (byte) value;
    }

    @Override
    public void onBlockChange(Level level, BlockPos blockPos,
                              BlockState newBlockState, BlockState oldBlockState,
                              @Block.UpdateFlags int flags, int maxUpdateDepth) {
        delegate.onBlockChange(level, blockPos, newBlockState, oldBlockState, flags, maxUpdateDepth);
        org.osmium.anticheat.OsmiumOcclusion.onBlockChanged(level, blockPos, newBlockState, oldBlockState); // Osmium - reveal candidates near mined blocks
        long ck = ((long) blockPos.getX() >> 4 & 0xFFFFFFFFL) | (((long) blockPos.getZ() >> 4 & 0xFFFFFFFFL) << 32);
        SHARED_REWRITES.remove(ck); // Osmium - drop shared far-view rewrite for this chunk
        PALETTE_CACHE.remove(ck); // Osmium - block edit may swap/grow section palettes
    }

    @Override
    public void onPlayerLeftClickBlock(Level level,
                                       BlockPos blockPos,
                                       ServerboundPlayerActionPacket.Action action,
                                       Direction direction,
                                       int worldHeight, int sequence) {
        delegate.onPlayerLeftClickBlock(level, blockPos, action, direction, worldHeight, sequence);
    }

    @Override
    public BlockState[] getPresetBlockStates(Level level, ChunkPos chunkPos, int chunkSectionY) {
        BlockState[] delegateStates = delegate.getPresetBlockStates(level, chunkPos, chunkSectionY);

        // For sections below the hiding threshold, inject the replacement block
        // into the palette preset values. This guarantees it's in every section's
        // palette during serialization — fixes fluid-only/air-only/single-block
        // sections that previously had holes because the replacement wasn't available.
        if (!enabled || chunkSectionY >= (hideBelow >> 4)) {
            return delegateStates;
        }

        if (delegateStates == null) {
            return new BlockState[] { replacementState };
        }

        for (BlockState state : delegateStates) {
            if (replacementState.equals(state)) return delegateStates;
        }

        BlockState[] combined = new BlockState[delegateStates.length + 1];
        System.arraycopy(delegateStates, 0, combined, 0, delegateStates.length);
        combined[delegateStates.length] = replacementState;
        return combined;
    }
}
