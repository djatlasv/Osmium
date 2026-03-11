package org.osmium.anticheat;

import io.papermc.paper.antixray.ChunkPacketBlockController;
import io.papermc.paper.antixray.ChunkPacketInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashSet;
import java.util.Set;

public class OsmiumChunkProcessor extends ChunkPacketBlockController {

    private final ChunkPacketBlockController delegate;
    private final int hideBelow;
    private final boolean enabled;
    private final int stoneId;
    private final int deepslateId;
    private final Set<Integer> hiddenBlockIds = new HashSet<>();

    public OsmiumChunkProcessor(ChunkPacketBlockController delegate, Level level, boolean enabled, int hideBelow) {
        this.delegate = delegate;
        this.enabled = enabled;
        this.hideBelow = hideBelow;
        this.stoneId = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        this.deepslateId = Block.BLOCK_STATE_REGISTRY.getId(Blocks.DEEPSLATE.defaultBlockState());
        if (enabled) {
            populateHiddenBlocks();
        }
    }

    private void populateHiddenBlocks() {
        Set<Block> deepslateFamily = Set.of(
            Blocks.DEEPSLATE,
            Blocks.COBBLED_DEEPSLATE,
            Blocks.POLISHED_DEEPSLATE,
            Blocks.DEEPSLATE_BRICKS,
            Blocks.CRACKED_DEEPSLATE_BRICKS,
            Blocks.DEEPSLATE_TILES,
            Blocks.CRACKED_DEEPSLATE_TILES,
            Blocks.CHISELED_DEEPSLATE,
            Blocks.DEEPSLATE_COAL_ORE,
            Blocks.DEEPSLATE_IRON_ORE,
            Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.DEEPSLATE_COPPER_ORE
        );

        Set<Block> baseIndicators = Set.of(
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.FURNACE,
            Blocks.BLAST_FURNACE,
            Blocks.SMOKER,
            Blocks.CRAFTING_TABLE,
            Blocks.BARREL,
            Blocks.ENCHANTING_TABLE,
            Blocks.ANVIL,
            Blocks.CHIPPED_ANVIL,
            Blocks.DAMAGED_ANVIL,
            Blocks.BREWING_STAND,
            Blocks.HOPPER,
            Blocks.DROPPER,
            Blocks.DISPENSER,
            Blocks.JUKEBOX,
            Blocks.NOTE_BLOCK,
            Blocks.BEACON
        );

        for (Block block : deepslateFamily) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                hiddenBlockIds.add(Block.BLOCK_STATE_REGISTRY.getId(state));
            }
        }

        for (Block block : baseIndicators) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                hiddenBlockIds.add(Block.BLOCK_STATE_REGISTRY.getId(state));
            }
        }
    }

    @Override
    public boolean shouldModify(ServerPlayer player, LevelChunk chunk) {
        return enabled || delegate.shouldModify(player, chunk);
    }

    @Override
    public ChunkPacketInfo<BlockState> getChunkPacketInfo(
            ClientboundLevelChunkWithLightPacket chunkPacket, LevelChunk chunk) {
        return delegate.getChunkPacketInfo(chunkPacket, chunk);
    }

    @Override
    public void modifyBlocks(ClientboundLevelChunkWithLightPacket chunkPacket,
                             ChunkPacketInfo<BlockState> chunkPacketInfo) {
        delegate.modifyBlocks(chunkPacket, chunkPacketInfo);
        if (!enabled || chunkPacketInfo == null) {
            return;
        }
        applyOsmiumPass(chunkPacket, chunkPacketInfo);
    }

    private void applyOsmiumPass(ClientboundLevelChunkWithLightPacket chunkPacket,
                                  ChunkPacketInfo<BlockState> chunkPacketInfo) {
        LevelChunk chunk = chunkPacketInfo.getChunk();
        int minSectionY = chunk.getMinSectionY();
        int hideBelowSection = (hideBelow >> 4);

        // Get the raw packet buffer — this is the byte array the client will receive
        byte[] buffer = chunkPacketInfo.getBuffer();
        if (buffer == null) return;

        // Reuse reader/writer per call — these are lightweight, no allocation overhead
        io.papermc.paper.antixray.BitStorageReader reader = new io.papermc.paper.antixray.BitStorageReader();
        io.papermc.paper.antixray.BitStorageWriter writer = new io.papermc.paper.antixray.BitStorageWriter();
        reader.setBuffer(buffer);
        writer.setBuffer(buffer);

        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            int sectionY = sectionIndex + minSectionY;

            // Only hide blocks in sections entirely below our Y threshold
            if (sectionY >= hideBelowSection) continue;

            // ChunkPacketInfo only has data for sections that were actually written
            // isWritten() returns false for empty/skipped sections — skip those
            if (!chunkPacketInfo.isWritten(sectionIndex)) continue;

            int bits = chunkPacketInfo.getBits(sectionIndex);
            // bits == 0 means this section was skipped by Paper's serializer
            if (bits == 0) continue;

            // Get the palette for this section so we can resolve block IDs
            // Java concept: generics — getPalette() returns Palette<BlockState>
            // which maps local palette index -> BlockState
            io.papermc.paper.antixray.ChunkPacketInfo<net.minecraft.world.level.block.state.BlockState> typedInfo = chunkPacketInfo;
            net.minecraft.world.level.chunk.Palette<net.minecraft.world.level.block.state.BlockState> palette =
                typedInfo.getPalette(sectionIndex);
            if (palette == null) continue;

            // Determine replacement ID for this section
            // Sections with sectionY < 0 are in deepslate territory, use deepslate replacement
            // Sections at sectionY >= 0 use stone
            // Java concept: ternary operator — condition ? valueIfTrue : valueIfFalse
            int replacementId = sectionY < 0 ? deepslateId : stoneId;

            // Point reader and writer at the start of this section's block data
            int index = chunkPacketInfo.getIndex(sectionIndex);
            reader.setBits(bits);
            reader.setIndex(index);
            writer.setBits(bits);
            writer.setIndex(index);

            // A chunk section is always 16x16x16 = 4096 blocks
            // We read every block, check if it should be hidden, write replacement or skip
            for (int i = 0; i < 4096; i++) {
                int paletteId = reader.read();

                // Resolve palette-local ID to global block state ID
                // Java concept: try-catch — palette.valueFor() can throw if the palette
                // was modified concurrently (race condition). We treat that as transparent.
                net.minecraft.world.level.block.state.BlockState blockState;
                try {
                    blockState = palette.valueFor(paletteId);
                } catch (Exception e) {
                    writer.skip();
                    continue;
                }

                if (blockState == null) {
                    writer.skip();
                    continue;
                }

                // Get the global registry ID for this block state
                int globalId = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(blockState);

                // If this block is in our hidden set, replace it — otherwise leave it alone
                if (hiddenBlockIds.contains(globalId)) {
                    writer.write(replacementId);
                } else {
                    writer.skip();
                }
            }

            // Flush any buffered writes for this section back to the byte array
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
