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

import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PaletteResize;

import java.util.HashSet;
import java.util.Set;

public class OsmiumChunkProcessor extends ChunkPacketBlockController {

    private final ChunkPacketBlockController delegate;
    private final int hideBelow;
    private final boolean enabled;
    private final BlockState stoneState = Blocks.STONE.defaultBlockState();
    private final BlockState deepslateState = Blocks.DEEPSLATE.defaultBlockState();
    private final int stoneGlobalId;
    private final int deepslateGlobalId;
    private final Set<Integer> hiddenBlockIds = new HashSet<>();

    private final ThreadLocal<ServerPlayer> currentPlayer = new ThreadLocal<>();

    public OsmiumChunkProcessor(ChunkPacketBlockController delegate, Level level, boolean enabled, int hideBelow) {
        this.delegate = delegate;
        this.enabled = enabled;
        this.hideBelow = hideBelow;
        this.stoneGlobalId = Block.BLOCK_STATE_REGISTRY.getId(stoneState);
        this.deepslateGlobalId = Block.BLOCK_STATE_REGISTRY.getId(deepslateState);
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
        // Store player so getChunkPacketInfo and modifyBlocks can access it
        currentPlayer.set(player);
        return enabled || delegate.shouldModify(player, chunk);
    }

    @Override
    public ChunkPacketInfo<BlockState> getChunkPacketInfo(
            ClientboundLevelChunkWithLightPacket chunkPacket, LevelChunk chunk) {
        ServerPlayer player = currentPlayer.get();
        if (!enabled || player == null) {
            return delegate.getChunkPacketInfo(chunkPacket, chunk);
        }
        // Create our info object that carries the player reference
        OsmiumChunkPacketInfo osmiumInfo = new OsmiumChunkPacketInfo(chunkPacket, chunk, player);
        // Also get delegate's info so Paper's xray pass still has what it needs
        ChunkPacketInfo<BlockState> delegateInfo = delegate.getChunkPacketInfo(chunkPacket, chunk);
        osmiumInfo.setDelegateInfo(delegateInfo);
        return osmiumInfo;
    }

    @Override
    public void modifyBlocks(ClientboundLevelChunkWithLightPacket chunkPacket,
                             ChunkPacketInfo<BlockState> chunkPacketInfo) {
        if (chunkPacketInfo instanceof OsmiumChunkPacketInfo osmiumInfo) {
            // Run delegate with its own info object
            ChunkPacketInfo<BlockState> delegateInfo = osmiumInfo.getDelegateInfo();
            delegate.modifyBlocks(chunkPacket, delegateInfo != null ? delegateInfo : chunkPacketInfo);

            if (!enabled) return;

            // Skip chunk hiding (ore/container pass) if player is horizontally nearby,
            // but always run y-level hiding (it has its own 3D proximity check)
            boolean skipChunkHiding = osmiumInfo.isPlayerNearby(org.osmium.OsmiumConfig.chunkHidingProximityRadius);
            applyOsmiumPass(chunkPacket, osmiumInfo, skipChunkHiding);
        } else {
            // Fallback — not our info object, just delegate
            delegate.modifyBlocks(chunkPacket, chunkPacketInfo);
        }
    }

    /**
     * Resolves the palette-local ID for a replacement block state.
     * GlobalPalette uses global registry IDs directly; local palettes need idFor() lookup.
     */
    private int getReplacementPaletteId(Palette<BlockState> palette, boolean deepslateRegion) {
        if (palette instanceof GlobalPalette) {
            return deepslateRegion ? deepslateGlobalId : stoneGlobalId;
        }
        BlockState replacement = deepslateRegion ? deepslateState : stoneState;
        return palette.idFor(replacement, PaletteResize.noResizeExpected());
    }

    private void applyOsmiumPass(ClientboundLevelChunkWithLightPacket chunkPacket,
                                  ChunkPacketInfo<BlockState> chunkPacketInfo,
                                  boolean skipChunkHiding) {
        LevelChunk chunk = chunkPacketInfo.getChunk();
        int minSectionY = chunk.getMinSectionY();
        int hideBelowSection = (hideBelow >> 4);

        byte[] buffer = chunkPacketInfo.getBuffer();
        if (buffer == null) return;

        io.papermc.paper.antixray.BitStorageReader reader = new io.papermc.paper.antixray.BitStorageReader();
        io.papermc.paper.antixray.BitStorageWriter writer = new io.papermc.paper.antixray.BitStorageWriter();
        reader.setBuffer(buffer);
        writer.setBuffer(buffer);

        if (!skipChunkHiding)
        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            int sectionY = sectionIndex + minSectionY;
            if (sectionY >= hideBelowSection) continue;
            if (!chunkPacketInfo.isWritten(sectionIndex)) continue;

            int bits = chunkPacketInfo.getBits(sectionIndex);
            if (bits == 0) continue;

            Palette<BlockState> palette = chunkPacketInfo.getPalette(sectionIndex);
            if (palette == null) continue;

            // Resolve replacement to a palette-local ID (not a global ID)
            int replacementPaletteId = getReplacementPaletteId(palette, sectionY < 0);
            if (replacementPaletteId < 0) continue; // replacement not in palette, skip section

            // Pre-scan palette to build a per-palette-index replace flag.
            // Palettes are small (typically <20 entries) vs 4096 blocks per section,
            // so this avoids valueFor + registry lookup + set check on every block.
            int paletteSize = palette.getSize();
            boolean[] shouldReplace = new boolean[paletteSize];
            boolean anyHidden = false;

            for (int pid = 0; pid < paletteSize; pid++) {
                BlockState state;
                try {
                    state = palette.valueFor(pid);
                } catch (Exception e) {
                    continue;
                }
                if (state == null) continue;

                int globalId = Block.BLOCK_STATE_REGISTRY.getId(state);
                if (hiddenBlockIds.contains(globalId)) {
                    shouldReplace[pid] = true;
                    anyHidden = true;
                }
            }

            // If no palette entries need hiding, skip the entire 4096-block scan
            if (!anyHidden) continue;

            int index = chunkPacketInfo.getIndex(sectionIndex);
            reader.setBits(bits);
            reader.setIndex(index);
            writer.setBits(bits);
            writer.setIndex(index);

            for (int i = 0; i < 4096; i++) {
                int paletteId = reader.read();
                if (paletteId < paletteSize && shouldReplace[paletteId]) {
                    writer.write(replacementPaletteId);
                } else {
                    writer.skip();
                }
            }

            writer.flush();
        }

        // Y-level hiding pass — replace ALL blocks below threshold with deepslate,
        // but reveal sections within 3D proximity of the player
        if (org.osmium.OsmiumConfig.yLevelHidingEnabled && chunkPacketInfo instanceof OsmiumChunkPacketInfo osmInfo) {
            int yHideSection = (org.osmium.OsmiumConfig.yLevelHidingThreshold >> 4);
            int proximityRadius = org.osmium.OsmiumConfig.chunkHidingProximityRadius;

            ServerPlayer player = osmInfo.getPlayer();
            int playerBlockY = player.blockPosition().getY();
            int playerChunkX = player.blockPosition().getX() >> 4;
            int playerChunkZ = player.blockPosition().getZ() >> 4;
            int chunkX = chunk.getPos().x;
            int chunkZ = chunk.getPos().z;
            int chunkProximity = (proximityRadius + 15) >> 4;
            boolean xzNear = Math.abs(playerChunkX - chunkX) <= chunkProximity
                          && Math.abs(playerChunkZ - chunkZ) <= chunkProximity;

            for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
                int sectionY = sectionIndex + minSectionY;
                if (sectionY >= yHideSection) continue;
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

                // Always use deepslate for y-level hiding
                int replacementPaletteId = getReplacementPaletteId(palette, true);
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
