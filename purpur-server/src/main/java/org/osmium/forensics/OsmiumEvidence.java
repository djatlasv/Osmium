package org.osmium.forensics;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * /evidence <player> [minutes] — compiles recorded packet forensics into a
 * per-category report: a human-readable summary (speed spikes, attack bursts,
 * target switching, filtered chat, GrimAC violations) plus a raw JSONL dump
 * of every captured row in the lookback window.
 *
 * In game the summary is echoed to the command source and the dump written to
 * evidence/. Discord gets the summary embed + the dump as a file attachment
 * (see OsmiumDiscordBot.handleEvidence).
 *
 * Compilation runs on a dedicated background thread — the command handler
 * only resolves the player and schedules.
 */
public class OsmiumEvidence {

    private static final Logger LOGGER = Logger.getLogger("Osmium-Forensics");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Osmium-Evidence");
        t.setDaemon(true);
        return t;
    });

    public OsmiumEvidence() {}

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("evidence")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"),
                                        org.osmium.OsmiumConfig.forensicsEvidenceMinutes))
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
                                        .executes(ctx -> run(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "minutes"))))));
    }

    private static int run(CommandSourceStack source, String targetName, int minutes) {
        if (!org.osmium.OsmiumConfig.forensicsEnabled || !OsmiumEvidenceDb.isRunning()) {
            source.sendFailure(Component.literal(
                    "\u00a7cForensics is not running (osmium.yml \u00a7fforensics.enabled\u00a7c)."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        UUID uuid;
        String resolvedName = targetName;
        ServerPlayer online = server.getPlayerList().getPlayerByName(targetName);
        if (online != null) {
            uuid = online.getUUID();
            resolvedName = online.getPlainTextName();
        } else {
            var resolved = server.services().nameToIdCache().get(targetName);
            if (resolved.isEmpty()) {
                source.sendFailure(Component.literal("\u00a7cUnknown player '" + targetName + "'."));
                return 0;
            }
            uuid = resolved.get().id();
            resolvedName = resolved.get().name();
        }
        final UUID fUuid = uuid;
        final String fName = resolvedName;
        final ServerPlayer sender = source.getPlayer();
        EXEC.execute(() -> {
            try {
                Compiled c = compile(fUuid, fName, minutes);
                if (c.summary.isEmpty()) {
                    if (sender != null) server.execute(() -> sender.sendSystemMessage(Component.literal(
                            "\u00a7cNo forensics data recorded for \u00a7f" + fName
                                    + "\u00a7c in the last " + minutes + " min.")));
                    return;
                }
                if (sender != null) {
                    List<String> lines = c.summary.discordLines();
                    server.execute(() -> {
                        sender.sendSystemMessage(Component.literal(
                                "\u00a7a\u00a7lEvidence: \u00a7f" + fName + " \u00a77(last " + minutes + " min)"));
                        for (String line : lines) {
                            sender.sendSystemMessage(Component.literal("\u00a77" + line));
                        }
                        sender.sendSystemMessage(Component.literal(
                                "\u00a7aRaw dump: \u00a7f" + c.file.getAbsolutePath()));
                    });
                }
                LOGGER.info("Evidence compiled for " + fName + " -> " + c.file.getAbsolutePath());
            } catch (Throwable t) {
                LOGGER.warning("Evidence compile failed: " + t);
                if (sender != null) server.execute(() -> sender.sendSystemMessage(
                        Component.literal("\u00a7cEvidence compile failed: " + t)));
            }
        });
        return 1;
    }

    /** Runs compile() on the evidence thread, then invokes the callback (still on that thread). */
    public static void compileAsync(UUID uuid, String name, int minutes,
                                    java.util.function.BiConsumer<Compiled, Throwable> callback) {
        EXEC.execute(() -> {
            try {
                callback.accept(compile(uuid, name, minutes), null);
            } catch (Throwable t) {
                callback.accept(null, t);
            }
        });
    }

    // ------------------------------------------------------------------
    // auto-evidence on /report
    // ------------------------------------------------------------------

    /**
     * Called from OsmiumReport after a report was delivered: compiles the
     * target's recent evidence and posts it to the report channel so staff
     * get the data alongside the report embed.
     */
    public static void autoForReport(org.osmium.OsmiumReport.Report report) {
        if (!OsmiumEvidenceDb.isRunning()) return;
        UUID uuid = report.targetUuid();
        String name = report.targetName();
        EXEC.execute(() -> {
            try {
                Compiled c = compile(uuid, name, org.osmium.OsmiumConfig.forensicsEvidenceMinutes);
                if (c.summary.isEmpty()) return;
                org.osmium.discord.OsmiumDiscordBot.pushEvidence(c.summary, c.file);
            } catch (Throwable t) {
                LOGGER.warning("Auto evidence failed for " + name + ": " + t);
            }
        });
    }

    // ------------------------------------------------------------------
    // compilation
    // ------------------------------------------------------------------

    /** Everything the report needs: metrics + the raw dump file. */
    public static final class Compiled {
        public Summary summary;
        public File file;
    }

    /**
     * Queries the DB and computes metrics. Blocking (SQLite reads) — call
     * from a background thread. Rows are capped to keep the dump sane.
     */
    public static Compiled compile(UUID uuid, String name, int minutes) throws Exception {
        long to = System.currentTimeMillis();
        long from = to - minutes * 60_000L;
        int cap = Math.max(20_000, minutes * 4_000);

        Summary s = new Summary();
        s.uuid = uuid.toString();
        s.name = name;
        s.from = from;
        s.to = to;
        s.minutes = minutes;
        s.moves = OsmiumEvidenceDb.moves(uuid, from, to, cap);
        s.attacks = OsmiumEvidenceDb.attacks(uuid, from, to, cap);
        s.chat = OsmiumEvidenceDb.chat(uuid, from, to, Math.min(cap, 10_000));
        s.digs = OsmiumEvidenceDb.digs(uuid, from, to, cap);
        s.grim = OsmiumEvidenceDb.grim(uuid, from, to, Math.min(cap, 10_000));
        s.compute();
        if (s.isEmpty()) return new Compiled(); // nothing recorded

        File dir = new File("evidence");
        if (!dir.exists()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File file = new File(dir, sanitize(name) + "-" + stamp + ".jsonl");
        writeJsonl(file, s);
        Compiled c = new Compiled();
        c.summary = s;
        c.file = file;
        return c;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static void writeJsonl(File file, Summary s) throws Exception {
        try (PrintWriter w = new PrintWriter(file, StandardCharsets.UTF_8)) {
            w.println(GSON.toJson(Map.of(
                    "type", "meta", "player", s.name, "uuid", s.uuid,
                    "from", s.from, "to", s.to, "minutes", s.minutes)));
            for (OsmiumEvidenceDb.MoveRow r : s.moves) w.println(GSON.toJson(r));
            for (OsmiumEvidenceDb.AttackRow r : s.attacks) w.println(GSON.toJson(r));
            for (OsmiumEvidenceDb.DigRow r : s.digs) w.println(GSON.toJson(r));
            for (OsmiumEvidenceDb.ChatRow r : s.chat) w.println(GSON.toJson(r));
            for (OsmiumEvidenceDb.GrimRow r : s.grim) w.println(GSON.toJson(r));
        }
    }

    // ------------------------------------------------------------------
    // summary metrics
    // ------------------------------------------------------------------

    /** Human-readable per-category metrics for the evidence window. */
    public static final class Summary {
        public String uuid;
        public String name;
        public long from;
        public long to;
        public int minutes;
        public List<OsmiumEvidenceDb.MoveRow> moves;
        public List<OsmiumEvidenceDb.AttackRow> attacks;
        public List<OsmiumEvidenceDb.ChatRow> chat;
        public List<OsmiumEvidenceDb.DigRow> digs;
        public List<OsmiumEvidenceDb.GrimRow> grim;

        // movement metrics
        public int moveWindows;
        public double maxSpeed;       // horizontal blocks/s (per window)
        public double avgSpeed;
        public int speedSpikes;       // windows over 9 b/s
        public double maxVertical;    // |vy| b/s
        public double packetRate;     // move packets/s (timer indicator)

        // combat metrics
        public int attackCount;
        public int maxAttacksPerSec;
        public int distinctTargets;
        public String topTarget = "";
        public int topTargetHits;

        // chat metrics
        public int chatCount;
        public int filteredCount;
        public List<String> filteredSamples = new ArrayList<>();

        // grim metrics
        public int grimEvents;
        public int grimSetbacks;
        public Map<String, Double> grimByCheck = new LinkedHashMap<>();

        // analyzer scores (0 = clean, 100 = max suspicion)
        public int movementScore;
        public int combatScore;
        public int chatScore;
        public int grimScore;
        public int overallScore;
        public String overallVerdict = "No data";
        public List<String> scoreReasons = new ArrayList<>();
        // combat extras computed during analysis
        public int reachHits;        // attacks beyond 3.3 blocks
        public double maxReach;
        public double attackIntervalCv; // stddev/mean of attack intervals (robotic = low)

        public boolean isEmpty() {
            return (moves == null || moves.isEmpty())
                    && (attacks == null || attacks.isEmpty())
                    && (chat == null || chat.isEmpty())
                    && (digs == null || digs.isEmpty())
                    && (grim == null || grim.isEmpty());
        }

        void compute() {
            if (moves != null && !moves.isEmpty()) {
                moveWindows = moves.size();
                long totalDur = 0;
                double totalDist = 0;
                long totalPackets = 0;
                for (OsmiumEvidenceDb.MoveRow r : moves) {
                    double durS = Math.max(0.05, r.durMs() / 1000.0);
                    double speed = r.dist() / durS;
                    totalDur += r.durMs();
                    totalDist += r.dist();
                    totalPackets += r.packets();
                    if (speed > maxSpeed) maxSpeed = speed;
                    if (speed > 9.0) speedSpikes++;
                    double vy = Math.abs(r.y1() - r.y0()) / durS;
                    if (vy > maxVertical) maxVertical = vy;
                }
                if (totalDur > 0) {
                    avgSpeed = totalDist / (totalDur / 1000.0);
                    packetRate = totalPackets / (totalDur / 1000.0);
                }
            }
            if (attacks != null && !attacks.isEmpty()) {
                attackCount = attacks.size();
                Map<String, Integer> byTarget = new LinkedHashMap<>();
                Map<Long, Integer> perSec = new LinkedHashMap<>();
                for (OsmiumEvidenceDb.AttackRow r : attacks) {
                    byTarget.merge(r.targetType(), 1, Integer::sum);
                    perSec.merge(r.ts() / 1000, 1, Integer::sum);
                }
                for (Map.Entry<Long, Integer> e : perSec.entrySet()) {
                    if (e.getValue() > maxAttacksPerSec) maxAttacksPerSec = e.getValue();
                }
                distinctTargets = byTarget.size();
                for (Map.Entry<String, Integer> e : byTarget.entrySet()) {
                    if (e.getValue() > topTargetHits) {
                        topTargetHits = e.getValue();
                        topTarget = e.getKey();
                    }
                }
            }
            if (chat != null && !chat.isEmpty()) {
                chatCount = chat.size();
                for (OsmiumEvidenceDb.ChatRow r : chat) {
                    if (r.filtered()) {
                        filteredCount++;
                        if (filteredSamples.size() < 10) filteredSamples.add(r.message());
                    }
                }
            }
            if (grim != null && !grim.isEmpty()) {
                grimEvents = grim.size();
                for (OsmiumEvidenceDb.GrimRow r : grim) {
                    if (r.setback()) grimSetbacks++;
                    grimByCheck.merge(r.check(), r.vl(), Double::sum);
                }
            }
            analyze();
        }

        // ------------------------------------------------------------------
        // heuristic analyzer — scores DIRECT investigation, they are not proof
        // ------------------------------------------------------------------

        private void analyze() {
            // --- movement ---
            double ms = 0;
            if (moveWindows > 0) {
                double spikeRatio = (double) speedSpikes / moveWindows;
                if (maxSpeed > 20) { ms += 40; scoreReasons.add("movement: max speed " + fmt(maxSpeed) + " b/s (fly/blink territory)"); }
                else if (maxSpeed > 14) { ms += 25; scoreReasons.add("movement: max speed " + fmt(maxSpeed) + " b/s"); }
                else if (maxSpeed > 11) { ms += 10; scoreReasons.add("movement: max speed " + fmt(maxSpeed) + " b/s (could be elytra/pearl)"); }
                if (spikeRatio > 0.3) { ms += 25; scoreReasons.add("movement: " + Math.round(spikeRatio * 100) + "% of windows over 9 b/s"); }
                if (packetRate > 30) { ms += 30; scoreReasons.add("movement: " + fmt(packetRate) + " move pkt/s (timer hack range)"); }
                else if (packetRate > 20) { ms += 15; scoreReasons.add("movement: " + fmt(packetRate) + " move pkt/s (elevated)"); }
                if (maxVertical > 3.0) { ms += 15; scoreReasons.add("movement: vertical " + fmt(maxVertical) + " b/s (could be elytra dive)"); }
                // on-ground toggle spam
                int gc = 0;
                for (OsmiumEvidenceDb.MoveRow r : moves) gc += r.groundChanges();
                double avgGc = (double) gc / moveWindows;
                if (avgGc > 8) { ms += 25; scoreReasons.add("movement: " + fmt(avgGc) + " on-ground toggles/window (ground spoof)"); }
                if (maxSpeed > 14 || spikeRatio > 0.3) scoreReasons.add("movement: verify NOT elytra/pearls before acting");
            }
            movementScore = clamp(ms);

            // --- combat ---
            double cs = 0;
            if (attackCount > 2) {
                double spanS = attackWindowSeconds();
                double cps = spanS > 0 ? attackCount / spanS : 0;
                if (cps > 15) { cs += 40; scoreReasons.add("combat: " + fmt(cps) + " CPS sustained (autoclicker range)"); }
                else if (cps > 10) { cs += 20; scoreReasons.add("combat: " + fmt(cps) + " CPS sustained"); }
                // reach
                int over = 0;
                for (OsmiumEvidenceDb.AttackRow r : attacks) {
                    double dx = r.x() - r.tx(), dy = r.y() - r.ty(), dz = r.z() - r.tz();
                    double reach = Math.sqrt(dx * dx + dy * dy + dz * dz) - 0.45; // ~target bounding radius
                    if (reach > maxReach) maxReach = reach;
                    if (reach > 3.3) over++;
                }
                reachHits = over;
                if (over > 0) {
                    double ratio = (double) over / attackCount;
                    cs += ratio > 0.5 ? 45 : 20;
                    scoreReasons.add("combat: " + over + "/" + attackCount + " attacks beyond 3.3 reach (max " + fmt(maxReach) + ")");
                }
                // interval regularity (autoclickers are metronomic)
                if (attackCount >= 6) {
                    double mean = 0;
                    for (int i = 1; i < attacks.size(); i++) mean += attacks.get(i).ts() - attacks.get(i - 1).ts();
                    mean /= (attacks.size() - 1);
                    double var = 0;
                    for (int i = 1; i < attacks.size(); i++) {
                        double d = attacks.get(i).ts() - attacks.get(i - 1).ts() - mean;
                        var += d * d;
                    }
                    var /= (attacks.size() - 1);
                    double cv = mean > 0 ? Math.sqrt(var) / mean : 1;
                    attackIntervalCv = cv;
                    if (cv < 0.08) { cs += 30; scoreReasons.add("combat: metronomic attack intervals (cv " + fmt(cv) + " — autoclicker signature)"); }
                    else if (cv < 0.15) { cs += 15; scoreReasons.add("combat: suspiciously regular attack intervals (cv " + fmt(cv) + ")"); }
                }
                // target switching (killaura multi-target)
                if (distinctTargets >= 3) {
                    int switches = 0;
                    for (int i = 1; i < attacks.size(); i++) {
                        if (!attacks.get(i).targetUuid().equals(attacks.get(i - 1).targetUuid())) switches++;
                    }
                    double switchesPerMin = spanS > 0 ? switches / (spanS / 60.0) : 0;
                    if (switchesPerMin > 10) { cs += 20; scoreReasons.add("combat: " + (int) switchesPerMin + " target switches/min (killaura pattern)"); }
                }
            }
            combatScore = clamp(cs);

            // --- chat ---
            if (chatCount > 0 && filteredCount > 0) {
                chatScore = clamp(Math.min(100, filteredCount * 12));
                scoreReasons.add("chat: " + filteredCount + "/" + chatCount + " messages caught by the filter (bypass attempts)");
            }

            // --- grim ---
            if (grimEvents > 0) {
                double totalVl = 0;
                for (double v : grimByCheck.values()) totalVl += v;
                grimScore = clamp((int) Math.min(100, totalVl * 2 + grimSetbacks * 5));
                scoreReasons.add("grim: " + fmt(totalVl) + " total VL across " + grimByCheck.size() + " check(s), " + grimSetbacks + " setbacks");
            }

            // --- overall ---
            double parts = 0, weighted = 0;
            if (moveWindows > 0) { weighted += movementScore * 0.30; parts += 0.30; }
            if (attackCount > 0) { weighted += combatScore * 0.30; parts += 0.30; }
            if (chatCount > 0) { weighted += chatScore * 0.20; parts += 0.20; }
            if (grimEvents > 0) { weighted += grimScore * 0.20; parts += 0.20; }
            overallScore = parts > 0 ? clamp((int) (weighted / parts)) : 0;
            overallVerdict = parts == 0 ? "No data" : verdict(overallScore);
        }

        private double attackWindowSeconds() {
            if (attacks.isEmpty()) return 0;
            return Math.max(1, (attacks.get(attacks.size() - 1).ts() - attacks.get(0).ts()) / 1000.0);
        }

        private static int clamp(double v) { return (int) Math.max(0, Math.min(100, v)); }

        private static String verdict(int score) {
            if (score < 10) return "Clean";
            if (score < 30) return "Low";
            if (score < 55) return "Suspicious";
            if (score < 75) return "Likely cheating";
            return "Cheating (high confidence)";
        }

        /** Compact per-category lines (in-game echo + Discord embed fields). */
        public List<String> discordLines() {
            List<String> out = new ArrayList<>();
            // verdict block first — this is what staff read
            if (moveWindows > 0 || attackCount > 0 || chatCount > 0 || grimEvents > 0) {
                out.add("Verdict: " + overallVerdict + " (" + overallScore + "%) — movement " + movementScore
                        + "%, combat " + combatScore + "%, chat " + chatScore + "%, grim " + grimScore + "%");
                for (String reason : scoreReasons) out.add("  " + reason);
                if (scoreReasons.isEmpty()) out.add("  no anomalies flagged in the lookback window");
            }
            out.add("Movement: " + moveWindows + " windows, avg " + fmt(avgSpeed) + " b/s, max "
                    + fmt(maxSpeed) + " b/s" + (speedSpikes > 0 ? " (" + speedSpikes + " spikes >9 b/s)" : "")
                    + ", " + String.format(java.util.Locale.ROOT, "%.1f", packetRate) + " pkt/s, maxVy "
                    + fmt(maxVertical) + " b/s");
            if (attackCount > 0) {
                out.add("Combat: " + attackCount + " attacks, peak " + maxAttacksPerSec + "/s, "
                        + distinctTargets + " distinct targets (top: " + topTarget + " x" + topTargetHits + ")");
            } else {
                out.add("Combat: no attacks recorded");
            }
            out.add("Chat: " + chatCount + " messages" + (filteredCount > 0
                    ? ", " + filteredCount + " FILTERED" : ""));
            for (String sample : filteredSamples) {
                out.add("  filtered: \"" + truncate(sample, 60) + "\"");
            }
            out.add("Digs: " + (digs == null ? 0 : digs.size()));
            if (grimEvents > 0) {
                StringBuilder sb = new StringBuilder("GrimAC: " + grimEvents + " violations ("
                        + grimSetbacks + " setbacks) — ");
                int shown = 0;
                for (Map.Entry<String, Double> e : grimByCheck.entrySet()) {
                    if (shown++ == 4) { sb.append("..."); break; }
                    if (shown > 1) sb.append(", ");
                    sb.append(e.getKey()).append(" ").append(fmt(e.getValue()));
                }
                out.add(sb.toString());
            } else {
                out.add("GrimAC: no violations recorded");
            }
            return out;
        }

        private static String fmt(double d) {
            return String.format(java.util.Locale.ROOT, "%.2f", d);
        }

        private static String truncate(String s, int max) {
            String one = s.replace("\n", " ");
            return one.length() <= max ? one : one.substring(0, max - 1) + "…";
        }
    }
}
