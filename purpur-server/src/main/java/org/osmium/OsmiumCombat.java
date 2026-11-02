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
        // Citizens NPCs are ServerPlayer subclasses — punching a command NPC
        // must never tag anyone (fake entities, no PvP).
        if (org.osmium.npc.OsmiumNpc.isCitizensNpc(victim.getBukkitEntity())
                || org.osmium.npc.OsmiumNpc.isCitizensNpc(attacker.getBukkitEntity())) return;

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
    // Generic command blocking (called from Commands.performCommand)
    // ------------------------------------------------------------------

    /**
     * Blocks configured commands while the sender is combat-tagged.
     * Runs at the NMS Brigadier dispatch point, so it covers vanilla AND
     * plugin commands (Essentials aliases, warps, ...) — anything a server
     * lists in combat.blocked-commands. Non-player senders (console,
     * functions, command blocks) and players with osmium.combat.bypass are
     * never blocked. Verb matching is namespace-agnostic: "essentials:home"
     * and "home" both match the entry "home" (and an entry may name the
     * full "plugin:command" form to be specific).
     *
     * @return true when the command was blocked and must not run
     */
    public static boolean blockCommand(net.minecraft.commands.CommandSourceStack source, String commandString) {
        if (OsmiumConfig.combatBlockedCommands.isEmpty()) return false;
        ServerPlayer player = source.getPlayer();
        if (player == null || !isTagged(player)) return false;
        if (player.getBukkitEntity().hasPermission("osmium.combat.bypass")) return false;

        String verb = verbOf(commandString);
        if (verb.isEmpty()) return false;
        for (String blocked : OsmiumConfig.combatBlockedCommands) {
            String b = blocked.trim().toLowerCase(java.util.Locale.ROOT);
            if (b.isEmpty()) continue;
            if (b.equals(verb) || stripNamespace(b).equals(verb)) {
                String msg = OsmiumConfig.combatBlockedCommandMessage;
                if (msg == null || msg.isEmpty()) return true;
                player.sendSystemMessage(Component.literal(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg)
                        .replace("{seconds}", String.valueOf(secondsLeft(player)))));
                return true;
            }
        }
        return false;
    }

    /** First word of the command, lowercase, leading slash stripped. */
    private static String verbOf(String command) {
        String s = command.trim();
        if (s.startsWith("/")) s = s.substring(1);
        int space = s.indexOf(' ');
        if (space >= 0) s = s.substring(0, space);
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    /** "essentials:home" -> "home", "home" -> "home" */
    private static String stripNamespace(String verb) {
        int colon = verb.indexOf(':');
        return colon >= 0 ? verb.substring(colon + 1) : verb;
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
