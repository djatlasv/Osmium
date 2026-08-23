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
        this.enabled = org.osmium.OsmiumConfig.chunkHidingEnabled;
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
            Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.COBBLED_DEEPSLATE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
            Blocks.CALCITE.defaultBlockState(),
            Blocks.SMOOTH_BASALT.defaultBlockState(),
            Blocks.AMETHYST_BLOCK.defaultBlockState(),
            Blocks.BUDDING_AMETHYST.defaultBlockState(),
        };
    }

    @Override
    public boolean shouldModify(ServerPlayer player, LevelChunk chunk) {
        OsmiumChunkPacketInfo.CURRENT_PLAYER.set(player);
        return enabled || delegate.shouldModify(player, chunk);
    }

    @Override
    public ChunkPacketInfo<BlockState> getChunkPacketInfo(
            ClientboundLevelChunkWithLightPacket chunkPacket, LevelChunk chunk) {
        ServerPlayer player = OsmiumChunkPacketInfo.CURRENT_PLAYER.get();
        OsmiumChunkPacketInfo.CURRENT_PLAYER.remove();
        if (!enabled || player == null) {
            return delegate.getChunkPacketInfo(chunkPacket, chunk);
        }
        OsmiumChunkPacketInfo osmiumInfo = new OsmiumChunkPacketInfo(chunkPacket, chunk, player);
        ChunkPacketInfo<BlockState> delegateInfo = delegate.getChunkPacketInfo(chunkPacket, chunk);
        osmiumInfo.setDelegateInfo(delegateInfo);
        return osmiumInfo;
    }

    @Override
    public void modifyBlocks(ClientboundLevelChunkWithLightPacket chunkPacket,
                             ChunkPacketInfo<BlockState> chunkPacketInfo) {
        if (chunkPacketInfo instanceof OsmiumChunkPacketInfo osmiumInfo) {
            ChunkPacketInfo<BlockState> delegateInfo = osmiumInfo.getDelegateInfo();
            delegate.modifyBlocks(chunkPacket, delegateInfo != null ? delegateInfo : chunkPacketInfo);

            if (!enabled) return;

            applyHiding(chunkPacket, osmiumInfo);
            applyRaytraceHiding(chunkPacket, osmiumInfo);
            applyLightHiding(chunkPacket, osmiumInfo);
        } else {
            delegate.modifyBlocks(chunkPacket, chunkPacketInfo);
        }
    }

    /**
     * Finds the best replacement block in the palette WITHOUT adding it.
     * Priority: configured block > deepslate/stone/tuff/etc > any solid block.
     * Returns -1 only if no suitable replacement exists.
     */
    private int findInPalette(Palette<BlockState> palette) {
        if (palette instanceof GlobalPalette) {
            return replacementGlobalId;
        }

        int size = palette.getSize();

        // Read all palette entries once — valueFor can be costly and the
        // tiered scans below would otherwise re-walk it many times.
        BlockState[] states = new BlockState[size];
        for (int i = 0; i < size; i++) {
            try { states[i] = palette.valueFor(i); } catch (Exception e) { states[i] = null; }
        }

        // Priority 1: exact configured block
        for (int i = 0; i < size; i++) {
            if (replacementState.equals(states[i])) return i;
        }

        // Priority 2: known stone-family fallbacks, in declared order
        for (BlockState fallback : fallbackStates) {
            if (fallback.equals(replacementState)) continue;
            for (int i = 0; i < size; i++) {
                if (fallback.equals(states[i])) return i;
            }
        }

        // Priority 3: any solid opaque non-fluid block
        for (int i = 0; i < size; i++) {
            BlockState state = states[i];
            if (state != null && !state.isAir()
                    && state.getFluidState().is(Fluids.EMPTY)
                    && state.isSolidRender()) {
                return i;
            }
        }

        // Priority 4 (last resort): ANY non-air block (even fluids, non-solid).
        // Hides lava pools, amethyst clusters, etc. — better than leaving gaps
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

        // 200-player scaling: when this player is far enough that the output is
        // player-independent (no proximity reveal), reuse a shared rewritten
        // buffer instead of redoing the bit rewrite per viewer.
        long chunkKey = ((long) chunk.getPos().x() & 0xFFFFFFFFL)
                | (((long) chunk.getPos().z() & 0xFFFFFFFFL) << 32);
        if (!antiXrayActive && !xzNear && org.osmium.OsmiumConfig.chunkHidingSharedRewrites) {
            Object cachedObj = SHARED_REWRITES.get(chunkKey);
            if (cachedObj instanceof SharedRewrite entry) {
                if (System.currentTimeMillis() - entry.atMillis() > SHARED_TTL_MS) {
                    SHARED_REWRITES.remove(chunkKey);
                } else {
                    byte[] shared = entry.data();
                    if (shared.length == buffer.length) {
                        System.arraycopy(shared, 0, buffer, 0, buffer.length);
                        return;
                    }
                }
            }
        }

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
                if (currentLen == replacementVarIntLen) {
                    int dataArrayIndex = chunkPacketInfo.getIndex(sectionIndex);
                    writeVarInt(buffer, dataArrayIndex - currentLen, replacementGlobalId);
                }
                continue;
            }

            int bits = chunkPacketInfo.getBits(sectionIndex);

            if (!chunkPacketInfo.isWritten(sectionIndex)) continue;

            int replacementPaletteId = findInPalette(palette);
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

        if (!antiXrayActive && !xzNear && org.osmium.OsmiumConfig.chunkHidingSharedRewrites) {
            // Bound memory: wholesale clear keeps worst case ~tens of MB.
            if (SHARED_REWRITES.size() > 512) SHARED_REWRITES.clear();
            long now = System.currentTimeMillis();
            SHARED_REWRITES.values().removeIf(o ->
                    now - ((SharedRewrite) o).atMillis() > SHARED_TTL_MS);
            SHARED_REWRITES.put(chunkKey, new SharedRewrite(buffer.clone(), now));
        }
    }

    // --- Shared far-view rewrites ---
    private static final long SHARED_TTL_MS = 30_000;
    private record SharedRewrite(byte[] data, long atMillis) {}
    /** chunkKey -> SharedRewrite */
    private static final java.util.concurrent.ConcurrentHashMap<Long, SharedRewrite> SHARED_REWRITES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Raytrace-based selective ore hiding. Unlike applyHiding (which rewrites
     * every entry below the threshold), this replaces ONLY target blocks that
     * the occlusion engine has determined have no line of sight to any player.
     * Cache lookups are O(1); heavy raycasting happens asynchronously.
     */
    private void applyRaytraceHiding(ClientboundLevelChunkWithLightPacket chunkPacket,
                                     OsmiumChunkPacketInfo info) {
        if (!org.osmium.OsmiumConfig.raytraceHidingEnabled) return;

        LevelChunk chunk = info.getChunk();
        long chunkKey = ((long) chunk.getPos().x() & 0xFFFFFFFFL)
                | (((long) chunk.getPos().z() & 0xFFFFFFFFL) << 32);
        // First time this chunk is sent: queue visibility computation.
        // This packet still fails open; the next one carries hidden ores.
        org.osmium.anticheat.OsmiumOcclusion.ensureComputed(
                (ServerLevel) chunk.getLevel(), chunkKey);
        byte[] buffer = info.getBuffer();
        if (buffer == null) return;

        Set<Block> targets = org.osmium.anticheat.OsmiumOcclusion.targetBlocks();

        io.papermc.paper.antixray.BitStorageReader reader = new io.papermc.paper.antixray.BitStorageReader();
        io.papermc.paper.antixray.BitStorageWriter writer = new io.papermc.paper.antixray.BitStorageWriter();
        reader.setBuffer(buffer);
        writer.setBuffer(buffer);

        int minSectionY = chunk.getMinSectionY();
        int sectionsCount = chunk.getSectionsCount();

        for (int sectionIndex = 0; sectionIndex < sectionsCount; sectionIndex++) {
            int bits = info.getBits(sectionIndex);
            if (bits <= 0) continue;
            if (!info.isWritten(sectionIndex)) continue;

            Palette<BlockState> palette = info.getPalette(sectionIndex);
            if (palette == null || palette.getSize() < 2) continue;

            // Precompute which palette ids are target blocks — skips per-entry
            // state lookups inside the hot loop below.
            int size = palette.getSize();
            boolean[] targetById = new boolean[size];
            boolean anyTarget = false;
            for (int id = 0; id < size; id++) {
                try {
                    BlockState st = palette.valueFor(id);
                    if (st != null && targets.contains(st.getBlock())) {
                        targetById[id] = true;
                        anyTarget = true;
                    }
                } catch (Exception ignored) {}
            }
            if (!anyTarget) continue;

            int replacementPaletteId = findInPalette(palette);
            if (replacementPaletteId < 0) continue;

            int sy = minSectionY + sectionIndex;
            int index = info.getIndex(sectionIndex);
            reader.setBits(bits);
            reader.setIndex(index);
            writer.setBits(bits);
            writer.setIndex(index);

            // Palette data is packed y<<8 | z<<4 | x — loop index == packed index
            for (int i = 0; i < 4096; i++) {
                int original = reader.read();
                int out = original;
                if (original < size && targetById[original]
                        && org.osmium.anticheat.OsmiumOcclusion.shouldHideBlock(
                                (ServerLevel) chunk.getLevel(), chunkKey, sy, i)) {
                    out = replacementPaletteId;
                }
                writer.write(out);
            }

            writer.flush();
        }
    }

    // --- Light hiding (defeats Light Finder hacks) ---

    private void applyLightHiding(ClientboundLevelChunkWithLightPacket chunkPacket,
                                   OsmiumChunkPacketInfo info) {
        if (!org.osmium.OsmiumConfig.chunkHidingHideLight) return;

        ClientboundLightUpdatePacketData lightData = chunkPacket.getLightData();
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

        zeroHiddenLightSections(lightData.getSkyYMask(), lightData.getSkyUpdates(),
                minLightSection, hideBelowSection, xzNear, playerBlockY, xzDistSq, proxSq);
        zeroHiddenLightSections(lightData.getBlockYMask(), lightData.getBlockUpdates(),
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
        org.osmium.anticheat.OsmiumOcclusion.onBlockChanged(level, blockPos); // Osmium - invalidate visibility cache
        long ck = ((long) blockPos.getX() >> 4 & 0xFFFFFFFFL) | (((long) blockPos.getZ() >> 4 & 0xFFFFFFFFL) << 32);
        SHARED_REWRITES.remove(ck); // Osmium - drop shared far-view rewrite for this chunk
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
