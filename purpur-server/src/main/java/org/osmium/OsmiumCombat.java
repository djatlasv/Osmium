package org.osmium;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat tag: PvP contact tags attacker AND victim for a configurable
 * duration. While tagged, teleport commands (/tpa /rtp /home /spawn ...)
 * are refused and in-flight teleport countdowns are cancelled — no
 * fighting your way out of a teleport.
 *
 * Feedback: action bar countdown while tagged, {combat} scoreboard
 * placeholder (seconds remaining, empty when free).
 */
public final class OsmiumCombat {

    private OsmiumCombat() {}

    /** player uuid -> tick at which the tag expires. */
    private static final Map<UUID, Long> TAGGED = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Core
    // ------------------------------------------------------------------

    public static boolean enabled() {
        return OsmiumConfig.combatEnabled;
    }

    /** True if the player is currently combat-tagged. */
    public static boolean isTagged(ServerPlayer player) {
        return enabled() && isTagged(player.getUUID(), player.level().getServer());
    }

    public static boolean isTagged(UUID uuid, MinecraftServer server) {
        Long until = TAGGED.get(uuid);
        // Only tag() slides the expiry — checking must never refresh the timer,
        // or spamming a blocked tp command would keep the tag alive forever.
        return until != null && until > server.getTickCount();
    }

    private static long expiryTick(MinecraftServer server) {
        return server.getTickCount() + Math.max(1, OsmiumConfig.combatDurationSeconds) * 20L;
    }

    /**
     * Tags both participants of a PvP exchange and cancels any pending
     * teleport countdowns they had running.
     */
    public static void tag(ServerPlayer victim, ServerPlayer attacker) {
        if (!enabled()) return;
        if (victim.getUUID().equals(attacker.getUUID())) return;

        MinecraftServer server = victim.level().getServer();
        long exp = expiryTick(server);

        boolean wasTaggedV = TAGGED.containsKey(victim.getUUID());
        boolean wasTaggedA = TAGGED.containsKey(attacker.getUUID());

        TAGGED.put(victim.getUUID(), exp);
        TAGGED.put(attacker.getUUID(), exp);

        // Fighting mid-teleport = cancel the teleport
        if (!wasTaggedV) cancelPendingTeleports(victim);
        if (!wasTaggedA) cancelPendingTeleports(attacker);
    }

    public static void clear(UUID playerUuid) {
        TAGGED.remove(playerUuid);
    }

    // ------------------------------------------------------------------
    // Teleport blocking helpers (called from the tp commands)
    // ------------------------------------------------------------------

    public static boolean blockTeleport(ServerPlayer player) {
        if (!isTagged(player)) return false;
        player.sendSystemMessage(Component.literal(
                "\u00a7cYou are in combat! Wait \u00a7e" + secondsLeft(player)
                        + "s\u00a7c before teleporting."));
        return true;
    }

    private static void cancelPendingTeleports(ServerPlayer player) {
        org.osmium.OsmiumTpa.cancelPendingTeleport(player.getUUID());
        org.osmium.OsmiumHomes.cancelPendingTeleport(player.getUUID());
        org.osmium.OsmiumRtp.cancelPendingTeleport(player.getUUID());
    }

    // ------------------------------------------------------------------
    // Tick driver — action bar + expiry cleanup
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        if (!enabled()) {
            if (!TAGGED.isEmpty()) TAGGED.clear();
            return;
        }
        long nowTick = server.getTickCount();

        Iterator<Map.Entry<UUID, Long>> it = TAGGED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());

            if (p == null || e.getValue() <= nowTick) {
                it.remove();
                if (p != null && OsmiumConfig.combatActionBar) {
                    p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                            Component.literal("\u00a7a\u2714 You are no longer in combat.")));
                }
                continue;
            }

            if (OsmiumConfig.combatActionBar && nowTick % 10 == 0) {
                long secs = (e.getValue() - nowTick + 19) / 20;
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                        Component.literal("\u00a7c\u2694 In Combat: \u00a7f" + secs + "s")));
            }
        }
    }

    private static int secondsLeft(ServerPlayer player) {
        Long until = TAGGED.get(player.getUUID());
        if (until == null) return 0;
        return (int) Math.max(0, (until - player.level().getServer().getTickCount() + 19) / 20);
    }

    /** Scoreboard placeholder value: seconds left, or "" when not tagged. */
    public static String placeholderFor(ServerPlayer player) {
        if (!enabled() || !TAGGED.containsKey(player.getUUID())) return "";
        return String.valueOf(secondsLeft(player));
    }
}
