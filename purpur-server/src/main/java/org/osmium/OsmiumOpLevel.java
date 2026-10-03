package org.osmium;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;

import java.util.Optional;

/**
 * /setop <player> <level> — set a player's op level (0-4) in game, without
 * hand-editing ops.json. Vanilla /op always grants level 4; this command can
 * assign the lower staff tiers used by chat-tags ([Mod] = 2, [Admin] = 3).
 *
 * SAFETY HIERARCHY (console bypasses everything):
 *  - You can only set levels STRICTLY BELOW your own (Owner 4 -> 0-3,
 *    Admin 3 -> 0-2, Mod 2 -> 0-1). Level 0 = deop.
 *  - You cannot modify a player whose CURRENT level is >= your own — admins
 *    can't demote each other, nobody can touch the owner.
 *  - Level 4 still requires vanilla /op (or console), so owners must be
 *    minted deliberately.
 *
 * Runs are staff actions and show up in the Discord staff log automatically
 * via the Commands.performCommand hook (the executor is an operator).
 */
public class OsmiumOpLevel {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Osmium-OpLevel");

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Always register — execution-time checks do the permission work.
        dispatcher.register(
                Commands.literal("setop")
                        .then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, 4))
                                        .executes(ctx -> {
                                            setOp(ctx.getSource(),
                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player"),
                                                    IntegerArgumentType.getInteger(ctx, "level"));
                                            return 1;
                                        }))));
    }

    private static void setOp(CommandSourceStack source, String targetNameRaw, int newLevel) {
        MinecraftServer server = MinecraftServer.getServer();
        String targetName = targetNameRaw == null ? "" : targetNameRaw.trim();

        var resolved = server.services().nameToIdCache().get(targetName);
        NameAndId target = resolved.orElse(null);
        if (target == null) {
            source.sendFailure(Component.literal("\u00a7cUnknown player '" + targetName + "' — they must have played before."));
            return;
        }

        ServerPlayer executorPlayer = source.getPlayer();
        if (executorPlayer == null) { // console — full authority
            apply(server, source, target, newLevel);
            return;
        }

        int executorLevel = currentLevel(server, executorPlayer.getGameProfile().name());
        if (executorLevel <= 0) {
            source.sendFailure(Component.literal("\u00a7cYou are not an operator."));
            return;
        }
        if (newLevel >= executorLevel) {
            source.sendFailure(Component.literal("\u00a7cYou can only set levels below your own (you are level "
                    + executorLevel + "). Level 4 needs vanilla /op or console."));
            return;
        }
        int targetLevel = currentLevel(server, target.name());
        if (targetLevel >= executorLevel) {
            source.sendFailure(Component.literal("\u00a7cYou can't modify " + target.name()
                    + " — their level (" + targetLevel + ") is >= yours (" + executorLevel + ")."));
            return;
        }

        apply(server, source, target, newLevel);
    }

    private static void apply(MinecraftServer server, CommandSourceStack source, NameAndId target, int newLevel) {
        if (newLevel <= 0) {
            server.getPlayerList().deop(target);
        } else {
            server.getPlayerList().op(target,
                    Optional.of(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(newLevel))),
                    Optional.empty());
        }

        String tag = tagName(newLevel);
        String message = "\u00a7aSet " + target.name() + " op level to \u00a7e" + newLevel
                + (tag.isEmpty() ? "" : " \u00a77(" + tag + "\u00a77)") + "\u00a7a.";
        source.sendSuccess(() -> Component.literal(message), false);
        LOGGER.info("[OpLevel] {} set {} op level to {}", source.getTextName(), target.name(), newLevel);

        ServerPlayer online = server.getPlayerList().getPlayer(target.id());
        if (online != null) {
            online.sendSystemMessage(Component.literal("\u00a7eYour op level was set to \u00a7f" + newLevel
                    + (tag.isEmpty() ? "" : " \u00a77(" + tag + "\u00a77)") + "\u00a7e."));
        }
    }

    /** Current op level of a player name from the ops list (0 = not op). */
    private static int currentLevel(MinecraftServer server, String name) {
        for (ServerOpListEntry entry : server.getPlayerList().getOps().getEntries()) {
            NameAndId user = entry.getUser();
            if (user != null && user.name().equalsIgnoreCase(name)) {
                return entry.permissions().level().id();
            }
        }
        return 0;
    }

    private static String tagName(int level) {
        return switch (level) {
            case 4 -> "Owner";
            case 3 -> "Admin";
            case 2 -> "Mod";
            case 1 -> "Helper";
            default -> "";
        };
    }
}