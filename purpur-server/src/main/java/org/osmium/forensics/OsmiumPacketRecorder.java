package org.osmium.forensics;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Always-on packet forensics recorder.
 *
 * NMS hooks call the on* methods on the tick thread; they only mutate a
 * per-player aggregation window and enqueue finished rows to the evidence
 * DB writer — never touch disk or the network here.
 *
 * Movement is aggregated into ~move-window-ms windows (start/end pos + rot,
 * packet count, horizontal distance, on-ground toggles). Attacks, digs and
 * chat are recorded as exact rows. Chat is captured RAW (pre-filter) with
 * the filter verdict, so filter-bypass attempts leave evidence.
 */
public final class OsmiumPacketRecorder {

    private static final Logger LOGGER = Logger.getLogger("Osmium-Forensics");

    private static volatile boolean running = false;
    private static final Map<UUID, MoveWindow> WINDOWS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> NAMES = new ConcurrentHashMap<>();

    private OsmiumPacketRecorder() {}

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    public static void start(File serverDir) {
        if (!org.osmium.OsmiumConfig.forensicsEnabled) return;
        OsmiumEvidenceDb.start(serverDir);
        if (!OsmiumEvidenceDb.isRunning()) return;
        running = true;
        OsmiumGrimBridge.start();
        LOGGER.info("Packet forensics enabled (movement windows " + org.osmium.OsmiumConfig.forensicsMoveWindowMs
                + "ms, retention " + org.osmium.OsmiumConfig.forensicsRetentionDays + "d)");
    }

    public static void stop() {
        if (!running) { OsmiumGrimBridge.stop(); return; }
        running = false;
        // flush live windows so nothing is lost on restart
        for (Map.Entry<UUID, MoveWindow> e : WINDOWS.entrySet()) {
            MoveWindow w = e.getValue();
            synchronized (w) {
                if (w.packets > 0) OsmiumEvidenceDb.enqueue(toRow(e.getKey(), w));
                w.packets = 0;
            }
        }
        OsmiumGrimBridge.stop();
        OsmiumEvidenceDb.stop();
    }

    public static boolean isRunning() { return running; }

    // ------------------------------------------------------------------
    // hooks (called from the NMS patch, tick thread)
    // ------------------------------------------------------------------

    public static void onJoin(ServerPlayer player) {
        if (!running) return;
        UUID uuid = player.getUUID();
        String name = player.getPlainTextName();
        NAMES.put(uuid, name);
        OsmiumEvidenceDb.enqueue(new OsmiumEvidenceDb.SessionRow(uuid.toString(), name, System.currentTimeMillis(), false));
    }

    public static void onQuit(ServerPlayer player) {
        if (!running) return;
        UUID uuid = player.getUUID();
        MoveWindow w = WINDOWS.remove(uuid);
        if (w != null) {
            synchronized (w) {
                if (w.packets > 0) OsmiumEvidenceDb.enqueue(toRow(uuid, w));
            }
        }
        OsmiumEvidenceDb.enqueue(new OsmiumEvidenceDb.SessionRow(uuid.toString(),
                player.getPlainTextName(), System.currentTimeMillis(), true));
        NAMES.remove(uuid);
    }

    public static void onMove(ServerPlayer player, ServerboundMovePlayerPacket packet) {
        if (!running) return;
        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();
        MoveWindow w = WINDOWS.computeIfAbsent(uuid, k -> new MoveWindow());
        boolean pos = packet.hasPosition();
        boolean rot = packet.hasRotation();
        synchronized (w) {
            if (w.packets == 0) {
                w.startTs = now;
                w.x0 = w.x1 = pos ? packet.getX(0.0) : player.getX();
                w.y0 = w.y1 = pos ? packet.getY(0.0) : player.getY();
                w.z0 = w.z1 = pos ? packet.getZ(0.0) : player.getZ();
                w.yaw0 = w.yaw1 = rot ? packet.getYRot(0.0F) : player.getYRot();
                w.pitch0 = w.pitch1 = rot ? packet.getXRot(0.0F) : player.getXRot();
                w.world = player.level().getWorld().getName();
                w.lastGround = player.onGround();
            }
            if (pos) {
                double nx = packet.getX(w.x1), ny = packet.getY(w.y1), nz = packet.getZ(w.z1);
                double dx = nx - w.x1, dy = ny - w.y1, dz = nz - w.z1;
                w.dist += Math.sqrt(dx * dx + dz * dz);
                w.x1 = nx; w.y1 = ny; w.z1 = nz;
            }
            if (rot) {
                w.yaw1 = packet.getYRot(w.yaw1);
                w.pitch1 = packet.getXRot(w.pitch1);
            }
            w.packets++;
            boolean g = player.onGround();
            if (g != w.lastGround) { w.groundChanges++; w.lastGround = g; }
            if (now - w.startTs >= org.osmium.OsmiumConfig.forensicsMoveWindowMs) {
                OsmiumEvidenceDb.enqueue(toRow(uuid, w));
                // next window starts where this one ended
                double x0 = w.x1, y0 = w.y1, z0 = w.z1;
                float yaw0 = w.yaw1, pitch0 = w.pitch1;
                w.packets = 0; w.dist = 0; w.groundChanges = 0;
                w.x0 = w.x1 = x0; w.y0 = w.y1 = y0; w.z0 = w.z1 = z0;
                w.yaw0 = w.yaw1 = yaw0; w.pitch0 = w.pitch1 = pitch0;
                w.startTs = now;
            }
        }
    }

    public static void onAttack(ServerPlayer player, Entity target) {
        if (!running || !org.osmium.OsmiumConfig.forensicsCaptureCombat || target == null) return;
        String world;
        try { world = player.level().getWorld().getName(); } catch (Exception e) { world = ""; }
        String type;
        try {
            type = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
        } catch (Exception e) {
            type = target.getBukkitEntity().getType().name();
        }
        String tUuid;
        try { tUuid = target.getUUID().toString(); } catch (Exception e) { tUuid = ""; }
        OsmiumEvidenceDb.enqueue(new OsmiumEvidenceDb.AttackRow(
                player.getUUID().toString(), name(player), System.currentTimeMillis(), world,
                player.getX(), player.getY(), player.getZ(),
                type, tUuid, target.getX(), target.getY(), target.getZ()));
    }

    public static void onDig(ServerPlayer player, ServerboundPlayerActionPacket packet, net.minecraft.world.level.Level level) {
        if (!running || !org.osmium.OsmiumConfig.forensicsCaptureCombat) return;
        BlockPos pos = packet.getPos();
        String action;
        try { action = packet.getAction().name(); } catch (Exception e) { action = "unknown"; }
        String block = "";
        try {
            BlockState state = level.getBlockState(pos);
            block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        } catch (Exception ignored) {}
        OsmiumEvidenceDb.enqueue(new OsmiumEvidenceDb.DigRow(
                player.getUUID().toString(), name(player), System.currentTimeMillis(),
                action, pos.getX(), pos.getY(), pos.getZ(), block));
    }

    /**
     * Captured from the chat handler BEFORE the filter acts — the filter
     * verdict is recorded alongside the raw text so bypass attempts keep
     * their evidence even though the message was blocked.
     */
    public static void onChat(ServerPlayer player, String rawMessage, boolean filtered, String filterMatch) {
        if (!running || !org.osmium.OsmiumConfig.forensicsCaptureChat) return;
        String world;
        try { world = player.level().getWorld().getName(); } catch (Exception e) { world = ""; }
        OsmiumEvidenceDb.enqueue(new OsmiumEvidenceDb.ChatRow(
                player.getUUID().toString(), name(player), System.currentTimeMillis(), world,
                rawMessage, filtered, filterMatch == null ? "" : filterMatch));
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static String name(ServerPlayer player) {
        return NAMES.computeIfAbsent(player.getUUID(), k -> player.getPlainTextName());
    }

    private static OsmiumEvidenceDb.MoveRow toRow(UUID uuid, MoveWindow w) {
        String n = NAMES.getOrDefault(uuid, "");
        long ts = System.currentTimeMillis();
        return new OsmiumEvidenceDb.MoveRow(uuid.toString(), n, ts, w.world == null ? "" : w.world,
                w.x0, w.y0, w.z0, w.yaw0, w.pitch0,
                w.x1, w.y1, w.z1, w.yaw1, w.pitch1,
                w.packets, w.dist, w.groundChanges, (int) Math.max(1, ts - w.startTs));
    }

    private static final class MoveWindow {
        long startTs;
        String world;
        double x0, y0, z0, x1, y1, z1;
        float yaw0, pitch0, yaw1, pitch1;
        int packets;
        double dist;
        int groundChanges;
        boolean lastGround;
    }
}
