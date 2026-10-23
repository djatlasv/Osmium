package org.osmium.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * /osbaltop — floating balance leaderboard (baltop) as a TextDisplay entity.
 *
 * A single vanilla TextDisplay (billboard center, no background, marker =
 * no hitbox) placed anywhere in any world. Persisted to osmium-baltop.json
 * (world + coords + entity uuid) and re-spawned automatically if the entity
 * is ever missing — the display survives restarts like any world entity.
 *
 * Balance data comes from the Vault economy provider (XConomy on this
 * server), accessed via reflection — no compile dependency. Scans run
 * ASYNC every refresh-seconds; candidate names come from online players +
 * usercache.json. UI updates are applied on the main thread.
 */
public class OsmiumBaltop {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Baltop");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Placement {
        String world;
        double x, y, z;
        String entityUuid;
    }

    private static Placement placement;
    private static org.bukkit.entity.TextDisplay display;
    private static Placement rankBase;        // anchor for the 3 rank NPCs
    private static final Placement[] RANK_OVERRIDE = new Placement[3]; // per-rank move override
    private static final int[] RANK_NPC_IDS = {-1, -1, -1};
    private static final String[] RANK_SKINS = new String[3]; // last applied skin per rank
    private static volatile java.util.List<String[]> lastTop; // last known top-3 for respawn re-apply
    private static File dataFile;
    private static MinecraftServer server;
    private static long lastScanMillis;
    private static volatile boolean scanning;
    private static int startupDelayTicks = -1;

    /** Lines ready to publish (async scan writes, main-thread tick applies). */
    private static volatile net.kyori.adventure.text.Component pendingText;
    private static volatile java.util.List<String[]> pendingTop;

    // Vault reflection
    private static Class<?> ECONOMY_CLASS;
    private static Method M_GET_BALANCE;
    private static Object vaultProvider;

    // ------------------------------------------------------------------ //

    public static void init(File serverDir) {
        dataFile = new File(serverDir, "osmium-baltop.json");
        load();
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("osbaltop")
                .requires(src -> src.getBukkitSender().isOp())
                .then(Commands.literal("here")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            placeHere(ctx.getSource(), p);
                            return 1;
                }))
                .then(Commands.literal("at")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .then(Commands.argument("x", StringArgumentType.word())
                                        .then(Commands.argument("y", StringArgumentType.word())
                                                .then(Commands.argument("z", StringArgumentType.word())
                                                        .executes(ctx -> {
                                                            placeAt(ctx.getSource(),
                                                                    StringArgumentType.getString(ctx, "world"),
                                                                    StringArgumentType.getString(ctx, "x"),
                                                                    StringArgumentType.getString(ctx, "y"),
                                                                    StringArgumentType.getString(ctx, "z"));
                                                            return 1;
                }))))))
                .then(Commands.literal("npc")
                        .then(Commands.literal("here")
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    placeRankNpcs(ctx.getSource(), p.level(), p.getX(), p.getEyeY() - 1.4, p.getZ());
                                    return 1;
                }))
                        .then(Commands.literal("at")
                                .then(Commands.argument("world", StringArgumentType.word())
                                        .then(Commands.argument("x", StringArgumentType.word())
                                                .then(Commands.argument("y", StringArgumentType.word())
                                                        .then(Commands.argument("z", StringArgumentType.word())
                                                                .executes(ctx -> {
                                                                    placeRankNpcsAt(ctx.getSource(),
                                                                            StringArgumentType.getString(ctx, "world"),
                                                                            StringArgumentType.getString(ctx, "x"),
                                                                            StringArgumentType.getString(ctx, "y"),
                                                                            StringArgumentType.getString(ctx, "z"));
                                                                    return 1;
                }))))))
                        .then(Commands.literal("move")
                                .then(Commands.argument("rank", IntegerArgumentType.integer(1, 3))
                                        .then(Commands.literal("here")
                                                .executes(ctx -> {
                                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                                    moveRankNpc(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "rank"),
                                                            p.level(), p.getX(), p.getEyeY() - 1.4, p.getZ());
                                                    return 1;
                }))
                                        .then(Commands.literal("at")
                                                .then(Commands.argument("world", StringArgumentType.word())
                                                        .then(Commands.argument("x", StringArgumentType.word())
                                                                .then(Commands.argument("y", StringArgumentType.word())
                                                                        .then(Commands.argument("z", StringArgumentType.word())
                                                                                .executes(ctx -> {
                                                                                    moveRankNpcAt(ctx.getSource(),
                                                                                            IntegerArgumentType.getInteger(ctx, "rank"),
                                                                                            StringArgumentType.getString(ctx, "world"),
                                                                                            StringArgumentType.getString(ctx, "x"),
                                                                                            StringArgumentType.getString(ctx, "y"),
                                                                                            StringArgumentType.getString(ctx, "z"));
                                                                                    return 1;
                }))))))
                                        .then(Commands.literal("reset")
                                                .executes(ctx -> {
                                                    resetRankNpc(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "rank"));
                                                    return 1;
                }))
                                ))
                        .then(Commands.literal("off")
                                .executes(ctx -> {
                                    removeRankNpcs();
                                    ctx.getSource().sendSuccess(() -> Component.literal("\u00a7aBaltop rank NPCs removed."), false);
                                    return 1;
                })))
                .then(Commands.literal("off")
                        .executes(ctx -> {
                            remove(ctx.getSource());
                            return 1;
                }))
                .then(Commands.literal("clean")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            org.bukkit.Location loc = ((org.bukkit.entity.Entity) p.getBukkitEntity()).getLocation();
                            int n = 0;
                            for (org.bukkit.entity.Entity e : loc.getWorld().getNearbyEntities(loc, 3, 3, 3)) {
                                if (e instanceof org.bukkit.entity.TextDisplay) { e.remove(); n++; }
                            }
                            final int removed = n;
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "\u00a7aRemoved " + removed + " stray baltop text display(s) nearby."), false);
                            return 1;
                        }))
                .then(Commands.literal("refresh")
                        .executes(ctx -> {
                            forceRefresh(ctx.getSource());
                            return 1;
                }))
                .then(Commands.literal("entries")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 25))
                                .executes(ctx -> {
                                    org.osmium.OsmiumConfig.baltopEntries =
                                            IntegerArgumentType.getInteger(ctx, "count");
                                    forceRefresh(ctx.getSource());
                                    return 1;
                                }))));
    }

    /** Tick driver from MinecraftServer.tickChildren (worlds + plugins live). */
    public static void tick(MinecraftServer srv) {
        server = srv;
        if (!org.osmium.OsmiumConfig.baltopEnabled || placement == null) {
            if (display != null) { removeDisplay(); }
            return;
        }

        // Startup grace: wait a few seconds before restoring (entities settle).
        if (startupDelayTicks < 0) startupDelayTicks = 100;
        if (startupDelayTicks > 0) { startupDelayTicks--; return; }

        if (liveDisplay() == null) {
            restore();
        }

        long now = System.currentTimeMillis();
        long interval = Math.max(10, org.osmium.OsmiumConfig.baltopRefreshSeconds) * 1000L;
        if (!scanning && now - lastScanMillis >= interval) {
            scan();
        }

        net.kyori.adventure.text.Component text = pendingText;
        org.bukkit.entity.TextDisplay live = display;
        if (text != null && live != null) {
            live.text(text);
            pendingText = null;
        }

        // Rank NPCs: restore despawned/destroyed ones, apply fresh names
        if (rankBase != null) {
            for (int i = 0; i < 3; i++) {
                Object npc = RANK_NPC_IDS[i] >= 0 ? OsmiumNpc.npcById(RANK_NPC_IDS[i]) : null;
                if (npc == null) {
                    spawnRankNpc(i);
                    continue;
                }
                if (!OsmiumNpc.npcSpawnedPkg(npc)) {
                    try {
                        org.bukkit.Location loc = rankLoc(rankBase, i);
                        if (loc != null) {
                            npc.getClass().getMethod("spawn", org.bukkit.Location.class).invoke(npc, loc);
                            // Citizens applies skins at spawn time; a skin set while
                            // the NPC was despawned can be missed — re-apply here.
                            java.util.List<String[]> t = lastTop;
                            if (t != null && i < t.size()) {
                                String[] e = t.get(i);
                                if (RANK_SKINS[i] == null || !RANK_SKINS[i].equals(e[1])) {
                                    RANK_SKINS[i] = e[1];
                                    OsmiumNpc.npcSetSkin(npc, e[1]);
                                }
                                OsmiumNpc.npcSetName(npc, rankColor(i + 1) + "#" + e[0] + " &f" + e[1]);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        java.util.List<String[]> top = pendingTop;
        if (top != null && rankBase != null) {
            pendingTop = null;
            for (int i = 0; i < 3; i++) {
                Object npc = RANK_NPC_IDS[i] >= 0 ? OsmiumNpc.npcById(RANK_NPC_IDS[i]) : null;
                if (npc == null) continue;
                if (i < top.size()) {
                    String[] e = top.get(i);
                    String prev = RANK_SKINS[i];
                    if (prev == null || !prev.equals(e[1])) {
                        RANK_SKINS[i] = e[1];
                        OsmiumNpc.npcSetSkin(npc, e[1]);
                    }
                    OsmiumNpc.npcSetName(npc, rankColor(i + 1) + "#" + e[0] + " &f" + e[1]);
                } else {
                    OsmiumNpc.npcSetName(npc, "\u00a77#" + (i + 1) + " \u2014");
                }
            }
        }
    }

    private static String rankColor(int rank) {
        return switch (rank) {
            case 1 -> "&6";
            case 2 -> "&7";
            default -> "&c";
        };
    }

    /** Centered row (rank 2 middle, rank 1 left, rank 3 right), unless the
     *  rank has an individual move override (/osbaltop npc move). */
    private static org.bukkit.Location rankLoc(Placement base, int i) {
        Placement o = RANK_OVERRIDE[i];
        if (o != null) {
            ServerLevel olvl = findLevelByName(o.world);
            return ollvlCheck(olvl, o);
        }
        ServerLevel lvl = findLevelByName(base.world);
        if (lvl == null) return null;
        double dx = (i - 1) * 1.6;
        return new org.bukkit.Location(lvl.getWorld(), base.x + dx, base.y, base.z);
    }

    private static org.bukkit.Location ollvlCheck(ServerLevel lvl, Placement o) {
        return lvl == null ? null : new org.bukkit.Location(lvl.getWorld(), o.x, o.y, o.z);
    }

    private static void placeRankNpcs(CommandSourceStack source, ServerLevel level, double x, double y, double z) {
        removeRankNpcs();
        rankBase = new Placement();
        rankBase.world = level.getWorld().getName();
        rankBase.x = x; rankBase.y = y; rankBase.z = z;
        save();
        source.sendSuccess(() -> Component.literal("\u00a7aBaltop rank NPCs anchored at \u00a7f" + rankBase.world
                + " " + (int) x + "," + (int) y + "," + (int) z
                + "\u00a7a — names update with every baltop scan."), false);
        for (int i = 0; i < 3; i++) spawnRankNpc(i);
        scan();
    }

    private static void placeRankNpcsAt(CommandSourceStack source, String worldName, String xs, String ys, String zs) {
        ServerLevel level = findLevel(source, worldName);
        if (level == null) return;
        Double x = parse(xs), y = parse(ys), z = parse(zs);
        if (x == null || y == null || z == null) {
            source.sendFailure(Component.literal("\u00a7cExpected numbers: x y z."));
            return;
        }
        placeRankNpcs(source, level, x, y, z);
    }

    private static void moveRankNpc(CommandSourceStack source, int rank, ServerLevel level, double x, double y, double z) {
        if (rankBase == null) {
            source.sendFailure(Component.literal("\u00a7cNo rank NPCs anchored — use /osbaltop npc first."));
            return;
        }
        Placement o = new Placement();
        o.world = level.getWorld().getName();
        o.x = x; o.y = y; o.z = z;
        RANK_OVERRIDE[rank - 1] = o;
        save();
        Object npc = RANK_NPC_IDS[rank - 1] >= 0 ? OsmiumNpc.npcById(RANK_NPC_IDS[rank - 1]) : null;
        org.bukkit.Location loc = new org.bukkit.Location(level.getWorld(), x, y, z);
        level.getWorld().getChunkAtAsync(((int) x) >> 4, ((int) z) >> 4).thenAccept(v -> {
            try {
                if (npc == null) {
                    spawnRankNpc(rank - 1);
                } else if (OsmiumNpc.npcSpawnedPkg(npc)) {
                    npc.getClass().getMethod("teleport", org.bukkit.Location.class,
                            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class)
                            .invoke(npc, loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                } else {
                    npc.getClass().getMethod("spawn", org.bukkit.Location.class).invoke(npc, loc);
                }
                source.sendSuccess(() -> Component.literal("\u00a7aRank \u00a7e" + rank + "\u00a7a NPC moved to \u00a7f"
                        + o.world + " " + (int) x + "," + (int) y + "," + (int) z + "\u00a7a."), false);
            } catch (Exception e) {
                source.sendFailure(Component.literal("\u00a7cMove failed: " + e));
            }
        });
    }

    private static void moveRankNpcAt(CommandSourceStack source, int rank, String worldName, String xs, String ys, String zs) {
        ServerLevel level = findLevel(source, worldName);
        if (level == null) return;
        Double x = parse(xs), y = parse(ys), z = parse(zs);
        if (x == null || y == null || z == null) {
            source.sendFailure(Component.literal("\u00a7cExpected numbers: x y z."));
            return;
        }
        moveRankNpc(source, rank, level, x, y, z);
    }

    private static void resetRankNpc(CommandSourceStack source, int rank) {
        if (rankBase == null) {
            source.sendFailure(Component.literal("\u00a7cNo rank NPCs anchored."));
            return;
        }
        RANK_OVERRIDE[rank - 1] = null;
        save();
        Object npc = RANK_NPC_IDS[rank - 1] >= 0 ? OsmiumNpc.npcById(RANK_NPC_IDS[rank - 1]) : null;
        org.bukkit.Location loc = rankLoc(rankBase, rank - 1);
        if (npc != null && loc != null) {
            try {
                if (OsmiumNpc.npcSpawnedPkg(npc)) {
                    npc.getClass().getMethod("teleport", org.bukkit.Location.class,
                            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class)
                            .invoke(npc, loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                } else {
                    npc.getClass().getMethod("spawn", org.bukkit.Location.class).invoke(npc, loc);
                }
            } catch (Exception e) {
                source.sendFailure(Component.literal("\u00a7cReset move failed: " + e));
            }
        }
        source.sendSuccess(() -> Component.literal("\u00a7aRank \u00a7e" + rank + "\u00a7a NPC back on the anchor row."), false);
    }

    private static final long[] RANK_RESPAWN_AT = {0, 0, 0};

    /** Spawns (or respawns) rank NPC i; id recorded in RANK_NPC_IDS.
     *  Rate-limited (5s) and chunk-gated: a transiently-empty registry lookup
     *  must never flood new NPCs, and spawning into an unloaded chunk leaves
     *  orphan entities that double on chunk load. */
    private static void spawnRankNpc(int i) {
        if (rankBase == null) return;
        long now = System.currentTimeMillis();
        if (now - RANK_RESPAWN_AT[i] < 5000) return;
        RANK_RESPAWN_AT[i] = now;
        ServerLevel level = findLevelByName(rankBase.world);
        if (level == null) return;
        org.bukkit.Location loc = rankLoc(rankBase, i);
        if (loc == null) return;
        org.bukkit.World world = level.getWorld();
        if (!world.isChunkLoaded(((int) loc.getX()) >> 4, ((int) loc.getZ()) >> 4)) return;
        Object npc = OsmiumNpc.npcById(RANK_NPC_IDS[i]);
        if (npc == null) {
            npc = OsmiumNpc.createNpc("player", "\u00a77#" + (i + 1) + " \u2014", loc);
            if (npc != null) {
                RANK_NPC_IDS[i] = OsmiumNpc.npcId(npc);
                save();
            }
        } else if (!OsmiumNpc.npcSpawnedPkg(npc)) {
            try {
                npc.getClass().getMethod("spawn", org.bukkit.Location.class).invoke(npc, loc);
            } catch (Exception ignored) {}
        }
    }

    private static void removeRankNpcs() {
        for (int i = 0; i < 3; i++) {
            if (RANK_NPC_IDS[i] >= 0) {
                Object npc = OsmiumNpc.npcById(RANK_NPC_IDS[i]);
                if (npc != null) OsmiumNpc.npcDestroy(npc);
                RANK_NPC_IDS[i] = -1;
            }
        }
        rankBase = null;
        save();
    }

    // ------------------------------------------------------------------ //
    //  Commands
    // ------------------------------------------------------------------ //

    private static void placeHere(CommandSourceStack source, ServerPlayer p) {
        place(source, p.level(), p.getX(), p.getEyeY() + 0.6, p.getZ());
    }

    private static void placeAt(CommandSourceStack source, String worldName, String xs, String ys, String zs) {
        ServerLevel level = findLevel(source, worldName);
        if (level == null) return;
        Double x = parse(xs), y = parse(ys), z = parse(zs);
        if (x == null || y == null || z == null) {
            source.sendFailure(Component.literal("\u00a7cExpected numbers: x y z."));
            return;
        }
        place(source, level, x, y, z);
    }

    private static void place(CommandSourceStack source, ServerLevel level, double x, double y, double z) {
        removeDisplay();
        Placement p = new Placement();
        p.world = level.getWorld().getName();
        p.x = x; p.y = y; p.z = z;
        p.entityUuid = null;
        placement = p;
        save();
        spawnDisplay(source);
        scan();
        source.sendSuccess(() -> Component.literal("\u00a7aBaltop display placed at \u00a7f" + p.world
                + " " + (int) x + "," + (int) y + "," + (int) z
                + "\u00a7a. Remove with \u00a7f/osbaltop off\u00a7a."), false);
    }

    private static void remove(CommandSourceStack source) {
        removeDisplay();
        // Sweep leftovers: the old orphan-respawn bug persisted extra display
        // copies in the chunk file — clear every TextDisplay at the recorded
        // spot (chunk is loaded when the command is run from nearby).
        if (placement != null) {
            try {
                ServerLevel level = findLevelByName(placement.world);
                if (level != null) {
                    org.bukkit.Location loc = new org.bukkit.Location(level.getWorld(),
                            placement.x, placement.y, placement.z);
                    for (org.bukkit.entity.Entity e : level.getWorld().getNearbyEntities(loc, 1.5, 2.0, 1.5)) {
                        if (e instanceof org.bukkit.entity.TextDisplay) e.remove();
                    }
                }
            } catch (Exception ignored) {}
        }
        placement = null;
        save();
        source.sendSuccess(() -> Component.literal("\u00a7aBaltop display removed."), false);
    }

    private static void forceRefresh(CommandSourceStack source) {
        scan();
        source.sendSuccess(() -> Component.literal("\u00a7aRefreshing baltop (async)..."), false);
    }

    // ------------------------------------------------------------------ //
    //  Display entity lifecycle
    // ------------------------------------------------------------------ //

    /** Resolves the live display entity. Trusts the UUID lookup over
     *  isValid(): CraftBukkit's isValid() = isAlive() && entity.valid, and
     *  entity.valid is only armed in the addEntity path — entities restored
     *  from a reloaded chunk can report isValid()=false while perfectly
     *  alive, which made every update spawn a fresh box instead of editing
     *  the existing one. A UUID hit means the entity exists in a world. */
    private static org.bukkit.entity.TextDisplay liveDisplay() {
        if (display != null && display.isValid()) return display;
        if (placement != null && placement.entityUuid != null) {
            try {
                org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(UUID.fromString(placement.entityUuid));
                if (e instanceof org.bukkit.entity.TextDisplay td) {
                    display = td;
                    return td;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static void restore() {
        // 1) Existing entity from a previous session — UUID hit is the
        //    liveness proof (isValid() can lie, see liveDisplay()).
        if (placement.entityUuid != null) {
            try {
                org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(UUID.fromString(placement.entityUuid));
                if (e instanceof org.bukkit.entity.TextDisplay td) {
                    display = td;
                    applyDisplayProperties(td);
                    return;
                }
            } catch (Exception ignored) {}
        }
        // 2) Re-spawn at the stored placement — ONLY once its chunk is actually
        //    loaded. Spawning into an unloaded chunk would leave the persisted
        //    original in the chunk file and double the display on next load.
        ServerLevel level = findLevelByName(placement.world);
        if (level == null) return;
        org.bukkit.World world = level.getWorld();
        if (!world.isChunkLoaded(((int) placement.x) >> 4, ((int) placement.z) >> 4)) return;
        if (display != null && display.isValid()) return;
        spawnDisplay(null);
    }

    private static void spawnDisplay(CommandSourceStack source) {
        try {
            ServerLevel level = findLevelByName(placement.world);
            if (level == null) return;
            org.bukkit.Location loc = new org.bukkit.Location(level.getWorld(),
                    placement.x, placement.y, placement.z);
            // Self-heal: remove ALL TextDisplays at this exact spot. We only
            // get here when the tracked entity is truly gone, so anything
            // still standing here is a stale leftover (the old "keep"
            // exception spared exactly the stale box we were replacing).
            try {
                for (org.bukkit.entity.Entity e : level.getWorld().getNearbyEntities(loc, 0.6, 1.0, 0.6)) {
                    if (e instanceof org.bukkit.entity.TextDisplay) e.remove();
                }
            } catch (Exception ignored) {}
            display = (org.bukkit.entity.TextDisplay) level.getWorld().spawn(loc,
                    org.bukkit.entity.TextDisplay.class, td -> configure(td));
            placement.entityUuid = display.getUniqueId().toString();
            save();
            if (source != null) {
                source.sendSuccess(() -> Component.literal("\u00a7aDisplay entity spawned."), false);
            }
        } catch (Exception e) {
            LOGGER.warn("[Baltop] display spawn failed: {}", e.toString());
        }
    }

    /** Entity properties only — never touches text (adopting must not
     *  reset the content). */
    private static void applyDisplayProperties(org.bukkit.entity.TextDisplay td) {
        td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        td.setDefaultBackground(false);
        td.setSeeThrough(false);
        td.setShadowed(true);
        td.setPersistent(true);
    }

    private static void configure(org.bukkit.entity.TextDisplay td) {
        applyDisplayProperties(td);
        td.text(legacy("\u00a76Loading baltop..."));
    }

    private static void removeDisplay() {
        if (display != null) {
            try { display.remove(); } catch (Exception ignored) {}
            display = null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Balance scan (async) -> pendingText
    // ------------------------------------------------------------------ //

    private static void scan() {
        if (scanning) return;
        scanning = true;
        lastScanMillis = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            try {
                Object provider = vaultProvider();
                if (provider == null) {
                    pendingText = legacy("\u00a7cBaltop unavailable: no Vault economy.");
                    return;
                }
                Map<String, Double> balances = new HashMap<>();
                Set<String> seen = new HashSet<>();

                // Online players first (exact names)
                for (org.bukkit.entity.Player p : new ArrayList<>(org.bukkit.Bukkit.getOnlinePlayers())) {
                    double b = balance(provider, p);
                    if (b > 0) balances.put(p.getName(), b);
                    seen.add(p.getUniqueId().toString());
                }

                // Then everyone the server remembers (usercache). Filters:
                //  - hasPlayedBefore() skips fake entries (GrimAC registers junk
                //    like "sim-hs-valid" via getOfflinePlayer -> usercache)
                //  - balance > 0 keeps the leaderboard to actual wealth
                for (String[] nameUuid : usercacheEntries()) {
                    if (seen.contains(nameUuid[1])) continue;
                    seen.add(nameUuid[1]);
                    try {
                        org.bukkit.OfflinePlayer off = org.bukkit.Bukkit.getOfflinePlayer(UUID.fromString(nameUuid[1]));
                        if (!off.hasPlayedBefore()) continue;
                        double b = balance(provider, off);
                        if (b > 0) balances.put(nameUuid[0], b);
                    } catch (Exception ignored) {}
                }

                List<Map.Entry<String, Double>> sorted = new ArrayList<>(balances.entrySet());
                sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                List<String[]> top = new ArrayList<>();
                for (int i = 0; i < sorted.size() && i < 3; i++) {
                    top.add(new String[]{String.valueOf(i + 1), sorted.get(i).getKey(), format(sorted.get(i).getValue())});
                }
                pendingTop = top;
                lastTop = top;

                int entries = Math.max(1, org.osmium.OsmiumConfig.baltopEntries);
                StringBuilder sb = new StringBuilder();
                sb.append(org.osmium.OsmiumConfig.baltopTitle).append('\n');
                int rank = 1;
                for (Map.Entry<String, Double> e : sorted) {
                    if (rank > entries) break;
                    sb.append(org.osmium.OsmiumConfig.baltopLineFormat
                            .replace("{rank}", String.valueOf(rank))
                            .replace("{name}", e.getKey())
                            .replace("{money}", format(e.getValue())))
                            .append('\n');
                    rank++;
                }
                if (rank == 1) sb.append("\u00a77No balances recorded yet.");
                pendingText = legacy(sb.toString());
            } catch (Exception e) {
                LOGGER.warn("[Baltop] scan failed: {}", e.toString());
                pendingText = legacy("\u00a7cBaltop scan error.");
            } finally {
                scanning = false;
            }
        });
    }

    private static double balance(Object provider, org.bukkit.OfflinePlayer player) {
        try {
            Object v = M_GET_BALANCE.invoke(provider, player);
            return v instanceof Number n ? n.doubleValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** [name, uuid] pairs from usercache.json (read fresh each scan — small file). */
    private static List<String[]> usercacheEntries() {
        List<String[]> out = new ArrayList<>();
        try {
            File f = new File(".", "usercache.json");
            if (!f.exists()) return out;
            List<Map<String, Object>> raw = GSON.fromJson(Files.readString(f.toPath()), List.class);
            if (raw == null) return out;
            for (Object o : raw) {
                if (o instanceof Map<?, ?> m) {
                    Object name = m.get("name"), uuid = m.get("uuid");
                    if (name instanceof String s && uuid instanceof String u) out.add(new String[]{s, u});
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String format(double money) {
        boolean whole = Math.abs(money - Math.rint(money)) < 0.005;
        String num = whole
                ? String.format(Locale.ROOT, "%,.0f", money)
                : String.format(Locale.ROOT, "%,.2f", money);
        return org.osmium.OsmiumConfig.baltopCurrencySymbol + num;
    }

    private static net.kyori.adventure.text.Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text.replace('\u00a7', '&'));
    }

    // ------------------------------------------------------------------ //
    //  Vault reflection
    // ------------------------------------------------------------------ //

    /** Vault API classes live in the Vault PLUGIN's classloader — the server
     *  classloader cannot see them. Failure is not latched: the next scan
     *  retries (plugin may enable later). */
    private static Object vaultProvider() {
        if (vaultProvider != null) return vaultProvider;
        synchronized (OsmiumBaltop.class) {
            if (vaultProvider != null) return vaultProvider;
            org.bukkit.plugin.Plugin vault = org.bukkit.Bukkit.getPluginManager().getPlugin("Vault");
            try {
                if (vault == null || !vault.isEnabled()) return null;
                ClassLoader cl = vault.getClass().getClassLoader();
                // Actual plugin jar uses net.milkbowl.*; the Maven artifact is com.github.milkbowl.*
                try {
                    ECONOMY_CLASS = cl.loadClass("net.milkbowl.vault.economy.Economy");
                } catch (ClassNotFoundException nfe) {
                    ECONOMY_CLASS = cl.loadClass("com.github.milkbowl.vault.economy.Economy");
                }
                Object rsp = org.bukkit.Bukkit.getServicesManager().getRegistration(ECONOMY_CLASS);
                if (rsp == null) return null;
                Object provider = rsp.getClass().getMethod("getProvider").invoke(rsp);
                M_GET_BALANCE = ECONOMY_CLASS.getMethod("getBalance", org.bukkit.OfflinePlayer.class);
                vaultProvider = provider;
                LOGGER.info("[Baltop] Vault economy provider: {}", provider.getClass().getName());
            } catch (Exception e) {
                LOGGER.info("[Baltop] Vault unavailable: {} (loader={})", e.toString(),
                        vault == null ? "none" : vault.getClass().getClassLoader().getClass().getName());
                vaultProvider = null;
            }
            return vaultProvider;
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers + persistence
    // ------------------------------------------------------------------ //

    private static ServerLevel findLevel(CommandSourceStack source, String name) {
        ServerLevel lvl = findLevelByName(name);
        if (lvl == null) source.sendFailure(Component.literal("\u00a7cUnknown world '" + name + "'."));
        return lvl;
    }

    private static ServerLevel findLevelByName(String name) {
        if (server == null) return null;
        for (ServerLevel lvl : server.getAllLevels()) {
            if (lvl.getWorld().getName().equalsIgnoreCase(name)) return lvl;
        }
        return null;
    }

    private static Double parse(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }

    private static final class Store {
        Placement display;
        Placement rankNpcBase;
        int[] rankNpcIds;
        Placement[] rankOverrides;
    }

    private static void load() {
        try {
            if (!dataFile.exists()) return;
            String raw = Files.readString(dataFile.toPath());
            Store st = GSON.fromJson(raw, Store.class);
            if (st != null && st.display != null) {
                placement = st.display;
                rankBase = st.rankNpcBase;
                if (st.rankNpcIds != null && st.rankNpcIds.length == 3) {
                    System.arraycopy(st.rankNpcIds, 0, RANK_NPC_IDS, 0, 3);
                }
                if (st.rankOverrides != null && st.rankOverrides.length == 3) {
                    System.arraycopy(st.rankOverrides, 0, RANK_OVERRIDE, 0, 3);
                }
            } else {
                // legacy single-placement file
                placement = GSON.fromJson(raw, Placement.class);
            }
        } catch (Exception e) {
            LOGGER.warn("[Baltop] failed to load osmium-baltop.json: {}", e.toString());
        }
    }

    private static void save() {
        try {
            Store st = new Store();
            st.display = placement;
            st.rankNpcBase = rankBase;
            st.rankNpcIds = RANK_NPC_IDS.clone();
            st.rankOverrides = RANK_OVERRIDE.clone();
            Files.writeString(dataFile.toPath(), GSON.toJson(st));
        } catch (Exception e) {
            LOGGER.warn("[Baltop] failed to save osmium-baltop.json: {}", e.toString());
        }
    }
}
