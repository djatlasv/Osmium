package org.osmium;

import net.kyori.adventure.text.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * Chat tags: prefixes the chat {sender} display name with an optional rank
 * tag (Owner/Admin/Mod derived from op level) and/or the team name in
 * brackets.
 *
 * Implementation: Paper renders signed player chat as "<{sender}> msg" where
 * {sender} is ServerPlayer.adventure$displayName. Maintaining that field is
 * signature-safe — the decoration is applied client-side from the chat type.
 * Refresh points: login (patch hook in placeNewPlayer) + every team
 * membership/name change (called directly from OsmiumTeam).
 *
 * Format: <[Owner] [TEAM] Steve> hello
 */
public final class OsmiumChatTags {

    private OsmiumChatTags() {}

    public static void apply(ServerPlayer player) {
        String name = player.getGameProfile().name();

        if (!OsmiumConfig.chatTagsEnabled) {
            clear(player, name);
            return;
        }

        String rank = rankOf(player);
        String team = OsmiumTeam.teamNameOf(player.getUUID());

        if (rank == null && team == null) {
            clear(player, name);
            return;
        }

        Component out = Component.empty();
        StringBuilder legacy = new StringBuilder();

        if (rank != null) {
            out = out.append(Component.text("[" + rank + "]", rankColor(rank)))
                    .append(Component.space());
            legacy.append(rankColorLegacy(rank)).append('[').append(rank).append("\u00a7r ");
        }
        if (team != null) {
            out = out.append(Component.text("[" + team + "]",
                            net.kyori.adventure.text.format.TextColor.color(0x55FFFF)))
                    .append(Component.space());
            legacy.append("\u00a7b[").append(team).append("\u00a7r ");
        }
        out = out.append(Component.text(name, net.kyori.adventure.text.format.NamedTextColor.WHITE));
        legacy.append("\u00a7f").append(name);

        player.adventure$displayName = out;
        player.displayName = legacy.toString();
    }

    /** Resets to the plain name (feature off / no tags). */
    private static void clear(ServerPlayer player, String name) {
        player.adventure$displayName = Component.text(name);
        player.displayName = name;
    }

    /** Re-applies tags to every online player (config toggle at runtime). */
    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            apply(p);
        }
    }

    /** Op level 4 = Owner, 3 = Admin, 2 = Mod; null for regular players. */
    private static String rankOf(ServerPlayer player) {
        if (player.permissions() instanceof LevelBasedPermissionSet set) {
            int id = set.level().id();
            if (id >= PermissionLevel.OWNERS.id()) return "Owner";
            if (id == PermissionLevel.ADMINS.id()) return "Admin";
            if (id == PermissionLevel.GAMEMASTERS.id()) return "Mod";
        }
        return null;
    }

    private static net.kyori.adventure.text.format.TextColor rankColor(String rank) {
        return switch (rank) {
            case "Owner" -> net.kyori.adventure.text.format.TextColor.color(0xFFAA00);   // gold
            case "Admin" -> net.kyori.adventure.text.format.TextColor.color(0xFF5555);   // red
            default -> net.kyori.adventure.text.format.TextColor.color(0xAA00AA);        // Mod: light purple
        };
    }

    private static String rankColorLegacy(String rank) {
        return switch (rank) {
            case "Owner" -> "\u00a76";
            case "Admin" -> "\u00a7c";
            default -> "\u00a75"; // Mod
        };
    }
}
