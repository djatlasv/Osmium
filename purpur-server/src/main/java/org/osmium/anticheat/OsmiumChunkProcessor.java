package org.osmium.anticheat;

import io.papermc.paper.antixray.ChunkPacketBlockController;
import io.papermc.paper.antixray.ChunkPacketInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
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

public class OsmiumChunkProcessor extends ChunkPacketBlockController {

    private final ChunkPacketBlockController delegate;
    private final boolean enabled;
    private final int hideBelow;
    private final int proximityRadius;
    private final BlockState replacementState;
    private final int replacementGlobalId;
    // Preferred fallback blocks when the configured block isn't in a section's palette
    private final BlockState[] fallbackStates;

    public OsmiumChunkProcessor(ChunkPacketBlockController delegate, Level level) {
        this.delegate = delegate;
        this.enabled = org.osmium.OsmiumConfig.chunkHidingEnabled;
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

        // Build fallback list: visually similar blocks to try when configured block isn't in palette
        this.fallbackStates = new BlockState[] {
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),
            Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.COBBLED_DEEPSLATE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
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
        } else {
            delegate.modifyBlocks(chunkPacket, chunkPacketInfo);
        }
    }

    /**
     * Finds the best replacement block in the palette WITHOUT adding it.
     * Priority: configured block > deepslate/stone/tuff/etc fallbacks > any solid block.
     * Returns -1 only if no suitable replacement exists (section must be skipped).
     */
    private int findInPalette(Palette<BlockState> palette) {
        if (palette instanceof GlobalPalette) {
            return replacementGlobalId;
        }

        int size = palette.getSize();

        // First pass: check for exact match (configured block)
        for (int i = 0; i < size; i++) {
            try {
                BlockState state = palette.valueFor(i);
                if (replacementState.equals(state)) return i;
            } catch (Exception e) { continue; }
        }

        // Second pass: try preferred fallback blocks in order
        for (BlockState fallback : fallbackStates) {
            if (fallback.equals(replacementState)) continue; // already checked
            for (int i = 0; i < size; i++) {
                try {
                    BlockState state = palette.valueFor(i);
                    if (fallback.equals(state)) return i;
                } catch (Exception e) { continue; }
            }
        }

        // Last resort: any solid opaque non-fluid block
        for (int i = 0; i < size; i++) {
            try {
                BlockState state = palette.valueFor(i);
                if (state != null && !state.isAir()
                        && state.getFluidState().is(Fluids.EMPTY)
                        && state.isSolidRender()) {
                    return i;
                }
            } catch (Exception e) { continue; }
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

        // XZ proximity in blocks, not chunks — more precise
        int chunkBlockX = chunk.getPos().x << 4;  // chunk origin X
        int chunkBlockZ = chunk.getPos().z << 4;  // chunk origin Z

        // Nearest block in chunk to player on XZ plane
        int nearestX = Math.max(chunkBlockX, Math.min(playerBlockX, chunkBlockX + 15));
        int nearestZ = Math.max(chunkBlockZ, Math.min(playerBlockZ, chunkBlockZ + 15));
        int xzDistSq = (playerBlockX - nearestX) * (playerBlockX - nearestX)
                      + (playerBlockZ - nearestZ) * (playerBlockZ - nearestZ);
        int proxSq = proximityRadius * proximityRadius;
        boolean xzNear = xzDistSq <= proxSq;

        io.papermc.paper.antixray.BitStorageReader reader = new io.papermc.paper.antixray.BitStorageReader();
        io.papermc.paper.antixray.BitStorageWriter writer = new io.papermc.paper.antixray.BitStorageWriter();
        reader.setBuffer(buffer);
        writer.setBuffer(buffer);

        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            int sectionY = sectionIndex + minSectionY;
            if (sectionY >= hideBelowSection) continue;
            if (!chunkPacketInfo.isWritten(sectionIndex)) continue;

            // 3D proximity: skip hiding if player is close to this section
            if (xzNear) {
                int sectionMinY = sectionY << 4;
                int sectionMaxY = sectionMinY + 15;
                int yDist = playerBlockY < sectionMinY ? sectionMinY - playerBlockY
                          : playerBlockY > sectionMaxY ? playerBlockY - sectionMaxY
                          : 0;
                if (yDist * yDist + xzDistSq <= proxSq) continue;
            }

            int bits = chunkPacketInfo.getBits(sectionIndex);
            if (bits == 0) continue;

            Palette<BlockState> palette = chunkPacketInfo.getPalette(sectionIndex);
            if (palette == null) continue;

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
    }

    @Override
    public void onBlockChange(Level level, BlockPos blockPos,
                              BlockState newBlockState, BlockState oldBlockState,
                              @Block.UpdateFlags int flags, int maxUpdateDepth) {
        delegate.onBlockChange(level, blockPos, newBlockState, oldBlockState, flags, maxUpdateDepth);
    }

    @Override
    public void onPlayerLeftClickBlock(ServerPlayerGameMode serverPlayerGameMode,
                                       BlockPos blockPos,
                                       ServerboundPlayerActionPacket.Action action,
                                       Direction direction,
                                       int worldHeight, int sequence) {
        delegate.onPlayerLeftClickBlock(serverPlayerGameMode, blockPos, action, direction, worldHeight, sequence);
    }

    @Override
    public BlockState[] getPresetBlockStates(Level level, ChunkPos chunkPos, int chunkSectionY) {
        return delegate.getPresetBlockStates(level, chunkPos, chunkSectionY);
    }
}
