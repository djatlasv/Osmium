package org.osmium.anticheat;

import io.papermc.paper.antixray.ChunkPacketInfo;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Extends ChunkPacketInfo to carry the player reference through the packet pipeline.
 * This lets modifyBlocks() know which player the chunk is being sent to,
 * so we can do proximity checks.
 *
 * Java concept: "extends" means inheritance — OsmiumChunkPacketInfo IS a ChunkPacketInfo,
 * so it can be used anywhere ChunkPacketInfo<BlockState> is expected.
 */
public class OsmiumChunkPacketInfo extends ChunkPacketInfo<BlockState> {

    private final ServerPlayer player;
    // Whether the delegate (Paper anti-xray) already ran its pass
    private ChunkPacketInfo<BlockState> delegateInfo;

    public OsmiumChunkPacketInfo(ClientboundLevelChunkWithLightPacket chunkPacket,
                                  LevelChunk chunk,
                                  ServerPlayer player) {
        super(chunkPacket, chunk);
        this.player = player;
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public ChunkPacketInfo<BlockState> getDelegateInfo() {
        return delegateInfo;
    }

    public void setDelegateInfo(ChunkPacketInfo<BlockState> delegateInfo) {
        this.delegateInfo = delegateInfo;
    }

    /**
     * Check if the player is within proximityRadius blocks of this chunk.
     *
     * Java concept: Math.abs() is absolute value. chunk positions are in chunk
     * coordinates (divide block coords by 16). We compare chunk distance, not
     * block distance, for performance — close enough for our purposes.
     */
    public boolean isPlayerNearby(int proximityRadiusBlocks) {
        LevelChunk chunk = getChunk();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Convert player block position to chunk position
        int playerChunkX = player.blockPosition().getX() >> 4;
        int playerChunkZ = player.blockPosition().getZ() >> 4;

        // Convert block radius to chunk radius (round up)
        int chunkRadius = (proximityRadiusBlocks + 15) >> 4;

        return Math.abs(playerChunkX - chunkX) <= chunkRadius
            && Math.abs(playerChunkZ - chunkZ) <= chunkRadius;
    }
}
