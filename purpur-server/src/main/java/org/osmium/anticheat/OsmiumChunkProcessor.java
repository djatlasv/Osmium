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

        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            int sectionY = sectionIndex + minSectionY;
            if (sectionY >= hideBelowSection) continue;
            // TODO: BitStorageReader/Writer pass — next session
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
