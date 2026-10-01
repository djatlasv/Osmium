package org.osmium.anticheat;

import io.papermc.paper.antixray.ChunkPacketInfo;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public class OsmiumChunkPacketInfo extends ChunkPacketInfo<BlockState> {

    static final ThreadLocal<ServerPlayer> CURRENT_PLAYER = new ThreadLocal<>();

    private final ServerPlayer player;
    private ChunkPacketInfo<BlockState> delegateInfo;

    public OsmiumChunkPacketInfo(LevelChunk chunk, ServerPlayer player) {
        super(chunk);
        this.player = player;
    }

    @Override
    public void setChunkPacket(ClientboundLevelChunkWithLightPacket chunkPacket) {
        super.setChunkPacket(chunkPacket);
        // 26.3: the packet is attached AFTER getChunkPacketInfo(); the
        // delegate's ChunkPacketInfoAntiXray would otherwise never see it
        // and its async obfuscate() NPEs on getChunkPacket().setReady(true).
        ChunkPacketInfo<BlockState> delegate = getDelegateInfo();
        if (delegate != null) delegate.setChunkPacket(chunkPacket);
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

    public boolean isPlayerNearby(int proximityRadiusBlocks) {
        LevelChunk chunk = getChunk();
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        int playerChunkX = player.blockPosition().getX() >> 4;
        int playerChunkZ = player.blockPosition().getZ() >> 4;
        int chunkRadius = (proximityRadiusBlocks + 15) >> 4;
        return Math.abs(playerChunkX - chunkX) <= chunkRadius
            && Math.abs(playerChunkZ - chunkZ) <= chunkRadius;
    }
}
