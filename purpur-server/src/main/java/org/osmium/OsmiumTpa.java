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
 * /tpa — ask to teleport to another player.
 *
 * Flow: /tpa <player> sends a request. The target gets a chat message with
 * clickable [ACCEPT]/[DENY] buttons (or types /tpaccept [player]).
 * On accept the TARGET is teleported to the REQUESTER's current position
 * after a short configurable countdown that cancels if they move.
 *
 * Requests auto-expire (configurable). Everything lives in memory —
 * nothing to persist.
 */
public class OsmiumTpa {

    private record Request(UUID fromUuid, String fromName, int expiresAtTick) {}

    /** Target player -> pending teleport to requester's position. */
    private record PendingTp(UUID targetUuid, UUID fromUuid, ServerLevel level,
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
                                    sendRequest(player, targetName);
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

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private static void sendRequest(ServerPlayer from, String targetNameRaw) {
        if (org.osmium.OsmiumCombat.blockTeleport(from)) return;
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
        REQUESTS.put(target.getUUID(), new Request(from.getUUID(), fromName, expiresAt));

        debug(fromName + " sent /tpa to " + target.getGameProfile().name());
        from.sendSystemMessage(Component.literal(
                "\u00a7aRequest sent to \u00a7f" + target.getGameProfile().name()
                        + "\u00a7a! Expires in \u00a7f" + OsmiumConfig.tpaTimeoutSeconds + "s\u00a7a."));

        target.sendSystemMessage(Component.literal(
                "\u00a7e" + fromName + " wants to teleport to you."));
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

        if (accept && org.osmium.OsmiumCombat.blockTeleport(target)) {
            ServerPlayer declinedBySystem = server.getPlayerList().getPlayer(request.fromUuid());
            REQUESTS.remove(target.getUUID());
            if (declinedBySystem != null) {
                declinedBySystem.sendSystemMessage(Component.literal(
                        "\u00a7c" + target.getPlainTextName() + " is in combat — request void."));
            }
            return;
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

        // Capture the requester's CURRENT position; the target travels to it
        ServerLevel level = from.level();
        var pos = from.position();

        if (PENDING.containsKey(target.getUUID())) {
            target.sendSystemMessage(Component.literal("\u00a7cYou already have a teleport in progress!"));
            return;
        }

        int delayTicks = Math.max(0, OsmiumConfig.tpaDelaySeconds) * 20;
        int teleportAt = server.getTickCount() + delayTicks;

        PENDING.put(target.getUUID(), new PendingTp(
                target.getUUID(), from.getUUID(), level,
                pos.x, pos.y, pos.z, from.getYRot(), from.getXRot(),
                target.getX(), target.getY(), target.getZ(),
                teleportAt));

        debug(target.getGameProfile().name() + " accepted /tpa from " + request.fromName()
                + " (delay=" + delayTicks + " ticks)");
        target.sendSystemMessage(Component.literal(
                "\u00a7aTeleporting to \u00a7f" + request.fromName() + "\u00a7a in \u00a7f"
                        + OsmiumConfig.tpaDelaySeconds + "s\u00a7a. Don't move!"));
        from.sendSystemMessage(Component.literal(
                "\u00a7a" + target.getGameProfile().name() + " accepted! They will arrive in "
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
            ServerPlayer player = server.getPlayerList().getPlayer(tp.targetUuid());

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
                ServerPlayer from = server.getPlayerList().getPlayer(tp.fromUuid());
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
            ServerPlayer from = server.getPlayerList().getPlayer(tp.fromUuid());
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
