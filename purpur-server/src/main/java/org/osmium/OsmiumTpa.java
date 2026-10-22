package org.osmium;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /tpa — teleport requests.
 *
 * /tpa <player>: ask to teleport YOURSELF to the target. On accept the
 * REQUESTER travels to the target's current position.
 * /tpahere <player>: ask the target to come to YOU. On accept the TARGET
 * travels to the requester's current position.
 *
 * The target gets a chat message with clickable [ACCEPT]/[DENY] buttons
 * (or types /tpaccept [player]). The player who travels moves after a short
 * configurable countdown that cancels if THEY move.
 *
 * Requests auto-expire (configurable). Everything lives in memory —
 * nothing to persist.
 */
public class OsmiumTpa {

    /** @param here true = /tpahere (target travels to requester); false = /tpa (requester travels to target). */
    private record Request(UUID fromUuid, String fromName, boolean here, int expiresAtTick) {}

    /** Traveling player -> teleport to the other player's position. */
    private record PendingTp(UUID moverUuid, UUID otherUuid, ServerLevel level,
                             double x, double y, double z,
                             float yRot, float xRot,
                             double startX, double startY, double startZ,
                             int teleportAtTick) {}

    private static final Map<UUID, Request> REQUESTS = new ConcurrentHashMap<>();     // target uuid -> request
    private static final Map<UUID, PendingTp> PENDING = new ConcurrentHashMap<>();    // moving player -> tp

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Always register — config may not be loaded at registration time.
        dispatcher.register(
                Commands.literal("tpa")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    if (!enabled()) return 0;
                                    String targetName = StringArgumentType.getString(ctx, "player");
                                    sendRequest(player, targetName, false);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!enabled()) return 0;
                            player.sendSystemMessage(Component.literal("\u00a7eUsage: /tpa <player>"));
                            return 1;
                        })
        );

        dispatcher.register(
                Commands.literal("tpahere")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    if (!enabled()) return 0;
                                    String targetName = StringArgumentType.getString(ctx, "player");
                                    sendRequest(player, targetName, true);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!enabled()) return 0;
                            player.sendSystemMessage(Component.literal("\u00a7eUsage: /tpahere <player>"));
                            return 1;
                        })
        );

        dispatcher.register(
                Commands.literal("tpaccept")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    if (!enabled()) return 0;
                                    respond(player, StringArgumentType.getString(ctx, "player"), true);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!enabled()) return 0;
                            respond(player, null, true);
                            return 1;
                        })
        );

        dispatcher.register(
                Commands.literal("tpdeny")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    if (!enabled()) return 0;
                                    respond(player, StringArgumentType.getString(ctx, "player"), false);
                                    return 1;
                                }))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!enabled()) return 0;
                            respond(player, null, false);
                            return 1;
                        })
        );
    }

    private static boolean enabled() {
        return OsmiumConfig.tpaEnabled;
    }

    /**
     * Paper's syncCommands generates minecraft.command.<name> permissions for
     * every Brigadier root node and defaults them to OP. Pre-register ours as
     * TRUE so non-op players can use /tpa /tpahere /tpaccept /tpdeny.
     */
    public static void initPermission() {
        for (String name : new String[]{"tpa", "tpahere", "tpaccept", "tpdeny"}) {
            try {
                org.bukkit.permissions.Permission perm = new org.bukkit.permissions.Permission(
                        "minecraft.command." + name,
                        "Allows use of the /" + name + " command",
                        org.bukkit.permissions.PermissionDefault.TRUE);
                org.bukkit.Bukkit.getPluginManager().addPermission(perm);
                debug("Registered Bukkit permission minecraft.command." + name);
            } catch (Exception e) {
                debug("Could not register Bukkit permission minecraft.command." + name + ": " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private static void sendRequest(ServerPlayer from, String targetNameRaw, boolean here) {
        // The traveler is the one who must not be in combat: the requester
        // for /tpa, the (not yet known) target for /tpahere — checked at accept.
        if (!here && org.osmium.OsmiumCombat.blockTeleport(from)) return;
        MinecraftServer server = from.level().getServer();
        String fromName = from.getGameProfile().name();

        ServerPlayer target = findOnline(server, targetNameRaw);
        if (target == null) {
            from.sendSystemMessage(Component.literal("\u00a7cPlayer '" + targetNameRaw + "' is not online."));
            return;
        }
        if (target.getUUID().equals(from.getUUID())) {
            from.sendSystemMessage(Component.literal("\u00a7cYou can't teleport to yourself."));
            return;
        }
        if (here && org.osmium.OsmiumCombat.blockTeleport(target)) {
            from.sendSystemMessage(Component.literal(
                    "\u00a7c" + target.getGameProfile().name() + " is in combat and can't teleport right now."));
            return;
        }

        // Cooldown between outgoing requests
        int cooldown = Math.max(0, OsmiumConfig.tpaCooldownSeconds);
        if (cooldown > 0) {
            long now = System.currentTimeMillis();
            Long last = LAST_USE.get(from.getUUID());
            if (last != null && now - last < cooldown * 1000L) {
                long remaining = cooldown - (now - last) / 1000L;
                from.sendSystemMessage(Component.literal(
                        "\u00a7cYou can use /tpa again in \u00a7e" + remaining + "s\u00a7c."));
                return;
            }
            LAST_USE.put(from.getUUID(), now);
        }

        // Overwrite any previous request this target has from someone else?
        // No — refuse if the TARGET already has a pending request (from anyone)
        // to avoid confusion, unless it's an identical repeat which refreshes.
        Request existing = REQUESTS.get(target.getUUID());
        if (existing != null && !existing.fromUuid().equals(from.getUUID())) {
            from.sendSystemMessage(Component.literal(
                    "\u00a7c" + target.getGameProfile().name() + " already has a pending teleport request."));
            return;
        }

        int expiresAt = server.getTickCount() + OsmiumConfig.tpaTimeoutSeconds * 20;
        REQUESTS.put(target.getUUID(), new Request(from.getUUID(), fromName, here, expiresAt));

        debug(fromName + " sent " + (here ? "/tpahere" : "/tpa") + " to " + target.getGameProfile().name());
        from.sendSystemMessage(Component.literal(
                "\u00a7aRequest sent to \u00a7f" + target.getGameProfile().name()
                        + "\u00a7a! Expires in \u00a7f" + OsmiumConfig.tpaTimeoutSeconds + "s\u00a7a."));

        if (here) {
            target.sendSystemMessage(Component.literal(
                    "\u00a7e" + fromName + " wants you to teleport to them."));
        } else {
            target.sendSystemMessage(Component.literal(
                    "\u00a7e" + fromName + " wants to teleport to you."));
        }
        target.sendSystemMessage(Component.empty()
                .append(chatButton("[ACCEPT]", "/tpaccept", ChatFormatting.GREEN))
                .append(Component.literal("  "))
                .append(chatButton("[DENY]", "/tpdeny", ChatFormatting.RED)));
    }

    /**
     * @param fromNameFilter null = use the only pending request, or complain if ambiguous
     */
    private static void respond(ServerPlayer target, String fromNameFilter, boolean accept) {
        MinecraftServer server = target.level().getServer();
        Request request = null;

        if (fromNameFilter != null) {
            ServerPlayer from = findOnline(server, fromNameFilter);
            if (from != null) {
                Request r = REQUESTS.get(target.getUUID());
                if (r != null && r.fromUuid().equals(from.getUUID())) request = r;
            }
            if (request == null) {
                target.sendSystemMessage(Component.literal(
                        "\u00a7cYou have no pending request from '" + fromNameFilter + "'."));
                return;
            }
        } else {
            request = REQUESTS.get(target.getUUID());
            if (request == null) {
                target.sendSystemMessage(Component.literal("\u00a7cYou have no pending teleport requests."));
                return;
            }
        }

        REQUESTS.remove(target.getUUID());
        ServerPlayer from = server.getPlayerList().getPlayer(request.fromUuid());

        if (!accept) {
            debug(target.getGameProfile().name() + " denied /tpa from " + request.fromName());
            target.sendSystemMessage(Component.literal("\u00a7cRequest denied."));
            if (from != null) {
                from.sendSystemMessage(Component.literal(
                        "\u00a7c" + target.getGameProfile().name() + " denied your teleport request."));
            }
            return;
        }

        if (from == null) {
            target.sendSystemMessage(Component.literal("\u00a7cThat player is no longer online."));
            return;
        }

        // Whoever travels must not be in combat (tag may have been applied
        // after the request was sent).
        ServerPlayer mover = request.here() ? target : from;
        if (org.osmium.OsmiumCombat.blockTeleport(mover)) {
            ServerPlayer other = request.here() ? from : target;
            other.sendSystemMessage(Component.literal(
                    "\u00a7c" + mover.getPlainTextName() + " is in combat — request void."));
            return;
        }

        // The traveler goes to the other player's CURRENT position
        ServerPlayer anchor = request.here() ? from : target;
        ServerLevel level = anchor.level();
        var pos = anchor.position();

        if (PENDING.containsKey(mover.getUUID())) {
            mover.sendSystemMessage(Component.literal("\u00a7cYou already have a teleport in progress!"));
            return;
        }

        int delayTicks = Math.max(0, OsmiumConfig.tpaDelaySeconds) * 20;
        int teleportAt = server.getTickCount() + delayTicks;

        PENDING.put(mover.getUUID(), new PendingTp(
                mover.getUUID(), anchor.getUUID(), level,
                pos.x, pos.y, pos.z, anchor.getYRot(), anchor.getXRot(),
                mover.getX(), mover.getY(), mover.getZ(),
                teleportAt));

        debug(mover.getGameProfile().name() + " accepted " + (request.here() ? "/tpahere" : "/tpa")
                + " from " + request.fromName() + " (delay=" + delayTicks + " ticks)");
        mover.sendSystemMessage(Component.literal(
                "\u00a7aTeleporting to \u00a7f" + anchor.getGameProfile().name() + "\u00a7a in \u00a7f"
                        + OsmiumConfig.tpaDelaySeconds + "s\u00a7a. Don't move!"));
        anchor.sendSystemMessage(Component.literal(
                "\u00a7a" + mover.getGameProfile().name() + " accepted! They will arrive in "
                        + OsmiumConfig.tpaDelaySeconds + "s."));
    }

    // ------------------------------------------------------------------
    // Tick — expiry, countdowns, move-cancel, teleport execution
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        if (REQUESTS.isEmpty() && PENDING.isEmpty()) return;
        long nowTick = server.getTickCount();

        Iterator<Map.Entry<UUID, Request>> reqIt = REQUESTS.entrySet().iterator();
        while (reqIt.hasNext()) {
            Map.Entry<UUID, Request> e = reqIt.next();
            if (nowTick >= e.getValue().expiresAtTick()) {
                reqIt.remove();
                ServerPlayer target = server.getPlayerList().getPlayer(e.getKey());
                if (target != null) {
                    target.sendSystemMessage(Component.literal("\u00a7cTeleport request expired."));
                }
            }
        }

        Iterator<Map.Entry<UUID, PendingTp>> pendIt = PENDING.entrySet().iterator();
        while (pendIt.hasNext()) {
            Map.Entry<UUID, PendingTp> e = pendIt.next();
            PendingTp tp = e.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(tp.moverUuid());

            if (player == null || player.level() != player.level().getServer().getLevel(player.level().dimension())) {
                pendIt.remove();
                continue;
            }

            // Move cancel: more than ~2 blocks from where they accepted
            double dx = player.getX() - tp.startX();
            double dy = player.getY() - tp.startY();
            double dz = player.getZ() - tp.startZ();
            if (dx * dx + dy * dy + dz * dz > 4.0) {
                pendIt.remove();
                player.sendSystemMessage(Component.literal("\u00a7cTeleport cancelled — you moved!"));
                ServerPlayer from = server.getPlayerList().getPlayer(tp.otherUuid());
                if (from != null) {
                    from.sendSystemMessage(Component.literal(
                            "\u00a7c" + player.getGameProfile().name() + " moved — teleport cancelled."));
                }
                continue;
            }

            if (nowTick < tp.teleportAtTick()) {
                long ticksLeft = tp.teleportAtTick() - nowTick;
                if (ticksLeft % 20 == 0) {
                    long secs = ticksLeft / 20;
                    if (secs > 0 && secs <= 3) {
                        player.sendSystemMessage(Component.literal(
                                "\u00a7eTeleporting in \u00a7f" + secs + "\u00a7e..."));
                    }
                }
                continue;
            }

            pendIt.remove();
            ServerPlayer from = server.getPlayerList().getPlayer(tp.otherUuid());
            if (from == null) {
                player.sendSystemMessage(Component.literal("\u00a7cTeleport cancelled — player left."));
                continue;
            }

            player.teleportTo(tp.level(), tp.x(), tp.y(), tp.z(),
                    Set.of(), tp.yRot(), tp.xRot(), true,
                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND);
            debug(player.getGameProfile().name() + " teleported to " + from.getGameProfile().name());
        }
    }

    /** Combat-tag support: cancels only the pending countdown teleport. */
    public static void cancelPendingTeleport(UUID playerUuid) {
        PENDING.remove(playerUuid);
    }

    /** Cleanup on disconnect: drop their incoming request, outgoing presence resolves via expiry. */
    public static void cancel(UUID playerUuid) {
        REQUESTS.remove(playerUuid);
        PENDING.remove(playerUuid);
        LAST_USE.remove(playerUuid);
    }

    // ------------------------------------------------------------------
    // Util
    // ------------------------------------------------------------------

    private static final Map<UUID, Long> LAST_USE = new ConcurrentHashMap<>();

    private static ServerPlayer findOnline(MinecraftServer server, String name) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private static Component chatButton(String text, String command, ChatFormatting color) {
        return Component.literal(text)
                .withStyle(style -> style
                        .withColor(color)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    private static void debug(String msg) {
        if (OsmiumConfig.tpaDebug) {
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Tpa").info("[DEBUG] {}", msg);
        }
    }
}
