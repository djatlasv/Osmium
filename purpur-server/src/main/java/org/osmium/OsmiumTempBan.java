package org.osmium;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /tempban <player> <duration> [reason] — combined-duration temporary bans
 * in game (vanilla /ban is permanent only).
 *
 * WHY THIS EXISTS: the GrimAC punishments we bundle call
 * `tempban %player% 1d ...`. Until now there was no in-game /tempban (the
 * v2.4.0 command was Discord-side only), so every Grim ban punishment was a
 * silent no-op — players only got [alert]/[webhook] entries and nobody was
 * actually banned.
 *
 * Duration syntax: 2h, 90m, 1d12h30m (units s/m/h/d/w), clamped to
 * [1 minute, 365 days]. The ban itself reuses OsmiumReport.banPlayer so
 * console, Discord and Grim all produce identical UserBanListEntry state.
 * Console dispatches (Grim) are recorded to the Discord staff log by the
 * OsmiumStaffLog punish capture.
 */
public class OsmiumTempBan {

    private static final Pattern DURATION = Pattern.compile("(\\d+)([smhdw])");
    private static final long MIN_MS = 60_000L;
    private static final long MAX_MS = 365L * 86400_000L;

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Always register — permission is checked at execution time so config
        // reloads and console dispatch (permission 4) always work.
        dispatcher.register(
                Commands.literal("tempban")
                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .executes(ctx -> ban(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "duration"),
                                                "Temp-banned"))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> ban(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "duration"),
                                                        StringArgumentType.getString(ctx, "reason")))))));
    }

    private static int ban(CommandSourceStack source, String targetName, String durationArg, String reason) {
        long ms = parseDuration(durationArg);
        if (ms < 0) {
            source.sendFailure(Component.literal(
                    "\u00a7cInvalid duration '" + durationArg + "' \u2014 use formats like \u00a7f2h\u00a7c, \u00a7f90m\u00a7c, \u00a7f1d12h\u00a7c (units s/m/h/d/w)."));
            return 0;
        }
        Date expires = new Date(System.currentTimeMillis() + ms);
        String who = source.getPlayer() != null ? source.getPlayer().getPlainTextName() : "Console";
        OsmiumReport.banPlayer(targetName, null, reason, expires, "Temp-banned by " + who);
        source.sendSuccess(() -> Component.literal(
                "\u00a7aTemp-banned \u00a7f" + targetName + "\u00a7a for \u00a7f" + formatDuration(ms)
                        + "\u00a7a \u2014 " + reason), true);
        return 1;
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600;  s %= 3600;
        long m = s / 60;    s %= 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.isEmpty()) sb.append(s).append("s");
        return sb.toString().trim();
    }

    /**
     * Parses combined durations like "2h", "90m", "1d12h30m". Returns the
     * duration in ms, or -1 if invalid/empty. Allowed units: s/m/h/d/w.
     */
    public static long parseDuration(String s) {
        if (s == null || s.isBlank()) return -1;
        Matcher m = DURATION.matcher(s.toLowerCase(Locale.ROOT));
        long total = 0;
        int matches = 0;
        int lastEnd = 0;
        while (m.find()) {
            if (m.start() != lastEnd) return -1; // stray characters between units
            long v = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "s" -> v;
                case "m" -> v * 60;
                case "h" -> v * 3600;
                case "d" -> v * 86400;
                default -> v * 604800; // w
            } * 1000L;
            matches++;
            lastEnd = m.end();
        }
        if (matches == 0 || lastEnd != s.length()) return -1;
        if (total < MIN_MS) return -1;
        return Math.min(total, MAX_MS);
    }
}
