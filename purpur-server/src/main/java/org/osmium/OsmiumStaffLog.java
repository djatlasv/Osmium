package org.osmium;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.osmium.discord.OsmiumDiscordBot;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Staff action log + Minecraft<->Discord identity linking.
 *
 * LINKING: /link (in game) hands out a one-time code; the staff member
 * completes it with /osmium link code:<code> in Discord (requires the helper
 * role or above, enforced at completion time). Links persist inside
 * osmium-discord-bot.json next to the bot's permission store, so they survive
 * restarts. /unlink (either side) removes the link.
 *
 * LOGGING: every command executed by staff in game (ops / osmium.staff
 * permission / linked accounts) is pushed to the Discord staff log channel,
 * annotated with the linked Discord identity when available. Discord-side
 * staff actions are audited by OsmiumDiscordBot into the same channel.
 * The channel itself is auto-created (private) by /osmium setup and can be
 * replaced with /osmium log-channel.
 *
 * The in-game capture point is Commands.performCommand (NMS, main thread),
 * so console/RCON/command-block dispatches are excluded (they are not
 * attributed to a staff member).
 */
public class OsmiumStaffLog {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Osmium-StaffLog");
    private static final long CODE_TTL_MS = 10 * 60_000L;
    private static final int MAX_PENDING = 64;

    /** A pending in-game link request awaiting completion in Discord. */
    public record PendingLink(UUID uuid, String name, long expiresAt) {
        public boolean expired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static final Map<String, PendingLink> PENDING = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------
    // Command registration
    // ------------------------------------------------------------------

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Always register — config may not be loaded yet at registration time.
        dispatcher.register(
                Commands.literal("link")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            linkStart(player);
                            return 1;
                        }));
        dispatcher.register(
                Commands.literal("unlink")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            unlinkSelf(player);
                            return 1;
                        }));
    }

    private static void linkStart(ServerPlayer player) {
        String linked = OsmiumDiscordBot.linkedDiscordName(player.getUUID());
        if (linked != null) {
            player.sendSystemMessage(Component.literal(
                    "\u00a7aYou are already linked to Discord: \u00a7f" + linked
                            + "\u00a77 (use /unlink to remove)"));
            return;
        }
        String code = generateCode();
        PENDING.put(code, new PendingLink(player.getUUID(), player.getGameProfile().name(),
                System.currentTimeMillis() + CODE_TTL_MS));
        if (PENDING.size() > MAX_PENDING) PENDING.values().removeIf(PendingLink::expired);
        player.sendSystemMessage(Component.literal(
                "\u00a7eFinish linking in the server's Discord: run \u00a7b/osmium link code:" + code
                        + "\u00a7e \u00a77(expires in 10 minutes \u2014 requires a staff role)"));
        LOGGER.info("[StaffLog] {} started a Discord link (code issued)", player.getGameProfile().name());
    }

    private static void unlinkSelf(ServerPlayer player) {
        if (OsmiumDiscordBot.removeLink(player.getUUID())) {
            player.sendSystemMessage(Component.literal("\u00a7aDiscord link removed."));
            OsmiumDiscordBot.pushStaffLog("\u274c " + player.getPlainTextName() + " unlinked their Discord account");
        } else {
            player.sendSystemMessage(Component.literal("\u00a7cYou are not linked to a Discord account."));
        }
    }

    private static String generateCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I/O/0/1 — avoid misreading
        StringBuilder sb = new StringBuilder(6);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        return sb.toString();
    }

    /** Used by the Discord bot: validates + consumes a code. Returns null if invalid/expired. */
    public static PendingLink claim(String code) {
        if (code == null) return null;
        PendingLink p = PENDING.remove(code.trim().toUpperCase(Locale.ROOT));
        if (p == null || p.expired()) return null;
        return p;
    }

    // ------------------------------------------------------------------
    // In-game staff command capture (called from Commands.performCommand)
    // ------------------------------------------------------------------

    public static void onCommandDispatch(CommandSourceStack source, String command) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return; // console / RCON / command blocks — not attributed to a staff member
        boolean staff = player.getBukkitEntity().hasPermission("osmium.staff")
                || OsmiumDiscordBot.isLinked(player.getUUID());
        if (!staff) return;
        String discord = OsmiumDiscordBot.linkedDiscordName(player.getUUID());
        String who = player.getPlainTextName()
                + (discord != null ? " (Discord: " + discord + ")" : " (unlinked)");
        LOGGER.info("[StaffLog] {} ran /{}", who, command);
        OsmiumDiscordBot.pushStaffLog("\u2699 " + who + " ran `" + command + "`");
    }
}