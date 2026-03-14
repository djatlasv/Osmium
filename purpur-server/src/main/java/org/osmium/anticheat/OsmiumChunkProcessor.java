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

public class OsmiumChunkProcessor extends ChunkPacketBlockController {

    private final ChunkPacketBlockController delegate;
    private final boolean enabled;
    private final int hideBelow;
    private final int proximityRadius;
    private final BlockState replacementState;
    private final int replacementGlobalId;

    public OsmiumChunkProcessor(ChunkPacketBlockController delegate, Level level) {
        this.delegate = delegate;
        this.enabled = org.osmium.OsmiumConfig.chunkHidingEnabled;
        this.hideBelow = org.osmium.OsmiumConfig.chunkHidingYThreshold;
        this.proximityRadius = org.osmium.OsmiumConfig.chunkHidingProximityRadius;

        // Resolve replacement block from config string
        String blockName = org.osmium.OsmiumConfig.chunkHidingBlock;
        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(blockName));
        if (block == null) {
            block = Blocks.DEEPSLATE;
            org.bukkit.Bukkit.getLogger().warning("[Osmium] Unknown block '" + blockName + "' in chunk-hiding.block, falling back to deepslate");
        }
        this.replacementState = block.defaultBlockState();
        this.replacementGlobalId = Block.BLOCK_STATE_REGISTRY.getId(this.replacementState);
    }

    @Override
    public boolean shouldModify(ServerPlayer player, LevelChunk chunk) {
        // Store player so getChunkPacketInfo and modifyBlocks can access it
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
            // Run Paper's anti-xray delegate first
            ChunkPacketInfo<BlockState> delegateInfo = osmiumInfo.getDelegateInfo();
            delegate.modifyBlocks(chunkPacket, delegateInfo != null ? delegateInfo : chunkPacketInfo);

            if (!enabled) return;

            applyHiding(chunkPacket, osmiumInfo);
        } else {
            delegate.modifyBlocks(chunkPacket, chunkPacketInfo);
        }
    }

    /**
     * Finds the replacement block's palette-local ID WITHOUT adding it.
     * Returns -1 if not in palette (section must be skipped).
     */
    private int findInPalette(Palette<BlockState> palette) {
        if (palette instanceof GlobalPalette) {
            return replacementGlobalId;
        }
        int size = palette.getSize();
        for (int i = 0; i < size; i++) {
            try {
                BlockState state = palette.valueFor(i);
                if (replacementState.equals(state)) return i;
            } catch (Exception e) {
                continue;
            }
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
        int playerBlockY = player.blockPosition().getY();
        int playerChunkX = player.blockPosition().getX() >> 4;
        int playerChunkZ = player.blockPosition().getZ() >> 4;
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        int chunkProximity = (proximityRadius + 15) >> 4;
        boolean xzNear = Math.abs(playerChunkX - chunkX) <= chunkProximity
                      && Math.abs(playerChunkZ - chunkZ) <= chunkProximity;

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
                if (yDist <= proximityRadius) continue;
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
