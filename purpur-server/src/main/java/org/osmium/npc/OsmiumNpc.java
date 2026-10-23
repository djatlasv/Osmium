package org.osmium.npc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * /osnpc — easy Citizens-backed command NPCs.
 *
 * Spawn an NPC anywhere (any world/dimension, any rotation), bind a command
 * to right-click, rotate it, or have it look at nearby players. The command
 * interface is deliberately flat and wordy-free: every subcommand takes the
 * NPC id first, actions after.
 *
 * Citizens is used at RUNTIME ONLY via reflection — no compile dependency,
 * graceful degradation when the plugin is absent. NPC metadata (name, type,
 * position, look-close) persists inside Citizens' own saves.yml; only the
 * command bindings live in osmium-npcs.json.
 *
 * Click routing is native: the 0022 patch calls onInteract from
 * ServerGamePacketListenerImpl#handleInteract (right-click only packet),
 * so no Bukkit listener registration is needed.
 *
 * Command placeholders: %p (clicker name), %u (clicker uuid).
 */
public class OsmiumNpc {

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Npc");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** JSON DTO — the root object mixes scalars and maps, so a generic
     *  TypeToken cannot describe it. */
    private static final class Store {
        int version;
        Map<String, Map<String, Object>> npcs;
    }

    /** Per-NPC command binding. mode: "console" | "player" | "say" (private
     *  NPC-branded chat line to the clicker — no global broadcast). */
    private static final class NpcData {
        String command;
        boolean asPlayer;
        int cooldownSeconds;
        String mode;
    }

    private static final Map<Integer, NpcData> BINDINGS = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Integer, Long>> COOLDOWNS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_CLICK = new HashMap<>(); // main-thread dedup
    private static File dataFile;
    private static boolean prunedOnce;

    // --- Citizens reflection bridge ---

    private static Class<?> NPC_CLASS;
    private static Class<?> LOOKCLOSE_CLASS;
    private static Class<?> SKIN_CLASS;
    private static Object REGISTRY;
    private static Method M_CREATE, M_GET_BY_ID, M_IS_NPC, M_GET_NPC, M_SPAWN,
            M_IS_SPAWNED, M_GET_ID, M_GET_ENTITY, M_FACE_LOCATION, M_TELEPORT,
            M_GET_OR_ADD_TRAIT, M_LOOK_ENABLE, M_LOOK_HEAD_ONLY, M_GET_FULL_NAME, M_DESTROY,
            M_GET_STORED_LOC, M_SKIN_SET;
    private static volatile boolean initialized;
    private static volatile boolean available;

    public static void init(File serverDir) {
        dataFile = new File(serverDir, "osmium-npcs.json");
        load();
    }

    // ------------------------------------------------------------------ //
    //  Command registration
    // ------------------------------------------------------------------ //

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("osnpc")
                .requires(src -> src.getBukkitSender().isOp())
                .then(Commands.literal("spawn")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> spawnHere(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), "player"))))
                .then(Commands.literal("spawnat")
                        .then(Commands.argument("world", StringArgumentType.word())
                                .then(Commands.argument("x", StringArgumentType.word())
                                        .then(Commands.argument("y", StringArgumentType.word())
                                                .then(Commands.argument("z", StringArgumentType.word())
                                                        .then(Commands.argument("yaw", StringArgumentType.word())
                                                                .then(Commands.argument("pitch", StringArgumentType.word())
                                                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                                                .executes(ctx -> spawnAt(ctx.getSource(),
                                                                                        StringArgumentType.getString(ctx, "world"),
                                                                                        StringArgumentType.getString(ctx, "x"),
                                                                                        StringArgumentType.getString(ctx, "y"),
                                                                                        StringArgumentType.getString(ctx, "z"),
                                                                                        StringArgumentType.getString(ctx, "yaw"),
                                                                                        StringArgumentType.getString(ctx, "pitch"),
                                                                                        StringArgumentType.getString(ctx, "name"),
                                                                                        "player"))))))))))
                .then(Commands.literal("cmd")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .then(Commands.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            bindCommand(IntegerArgumentType.getInteger(ctx, "id"),
                                                    StringArgumentType.getString(ctx, "command"), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("cmdas")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .then(Commands.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            bindCommand(IntegerArgumentType.getInteger(ctx, "id"),
                                                    StringArgumentType.getString(ctx, "command"), true);
                                            return 1;
                                        }))))
                .then(Commands.literal("say")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            bindSay(IntegerArgumentType.getInteger(ctx, "id"),
                                                    StringArgumentType.getString(ctx, "message"));
                                            return 1;
                                        }))))
                .then(Commands.literal("cooldown")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                                        .executes(ctx -> {
                                            setCooldown(IntegerArgumentType.getInteger(ctx, "id"),
                                                    IntegerArgumentType.getInteger(ctx, "seconds"));
                                            return 1;
                                        }))))
                .then(Commands.literal("rotate")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .then(Commands.argument("yaw", StringArgumentType.word())
                                        .executes(ctx -> rotate(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "id"),
                                                StringArgumentType.getString(ctx, "yaw"), "0"))
                                        .then(Commands.argument("pitch", StringArgumentType.word())
                                                .executes(ctx -> rotate(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "yaw"),
                                                        StringArgumentType.getString(ctx, "pitch")))))))
                .then(Commands.literal("faceme")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    faceMe(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"));
                                    return 1;
                                })))
                .then(Commands.literal("lookclose")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .then(Commands.literal("on")
                                        .executes(ctx -> lookClose(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "id"), true))
                                        .then(Commands.literal("head")
                                                .executes(ctx -> lookCloseHead(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "id"), true))))
                                .then(Commands.literal("off")
                                        .executes(ctx -> lookClose(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "id"), false)))))
                .then(Commands.literal("move")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    moveTo(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"), null, null, null, null);
                                    return 1;
                                })
                                .then(Commands.argument("x", StringArgumentType.word())
                                        .then(Commands.argument("y", StringArgumentType.word())
                                                .then(Commands.argument("z", StringArgumentType.word())
                                                        .executes(ctx -> moveTo(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "x"),
                                                                StringArgumentType.getString(ctx, "y"),
                                                                StringArgumentType.getString(ctx, "z"), null))
                                                        .then(Commands.argument("world", StringArgumentType.word())
                                                                .executes(ctx -> moveTo(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "id"),
                                                                        StringArgumentType.getString(ctx, "x"),
                                                                        StringArgumentType.getString(ctx, "y"),
                                                                        StringArgumentType.getString(ctx, "z"),
                                                                        StringArgumentType.getString(ctx, "world")))))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    info(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"));
                                    return 1;
                                })))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            list(ctx.getSource());
                            return 1;
                        }))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    remove(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"));
                                    return 1;
                                })));
        dispatcher.register(root);
    }

    // ------------------------------------------------------------------ //
    //  Click routing (native hook from handleInteract)
    // ------------------------------------------------------------------ //

    public static void onInteract(ServerPlayer player, Entity target) {
        if (!ensureBridge() || target == null || player == null) return;
        try {
            Object bukkitEntity = target.getBukkitEntity();
            if (!(Boolean) M_IS_NPC.invoke(REGISTRY, bukkitEntity)) return;
            Object npc = M_GET_NPC.invoke(REGISTRY, bukkitEntity);
            if (npc == null) return;
            int id = (Integer) M_GET_ID.invoke(npc);
            NpcData data = BINDINGS.get(id);
            if (data == null) return; // plain NPC, no command bound
            if (!npcSpawned(npc)) return;

            long now = System.currentTimeMillis();
            Long last = LAST_CLICK.get(player.getUUID());
            if (last != null && now - last < 250) return; // both-hands / both-packet dedup
            LAST_CLICK.put(player.getUUID(), now);
            if (LAST_CLICK.size() > 2048) LAST_CLICK.clear();

            if (data.cooldownSeconds > 0) {
                Map<Integer, Long> perNpc = COOLDOWNS.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
                Long until = perNpc.get(id);
                if (until != null && now < until) {
                    long wait = (until - now + 999) / 1000;
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(
                            Component.literal("\u00a77Wait \u00a7e" + wait + "s\u00a77...")));
                    return;
                }
                perNpc.put(id, now + data.cooldownSeconds * 1000L);
                if (perNpc.size() > 256) perNpc.clear();
            }

            String cmd = data.command;
            if (cmd == null || cmd.isBlank()) return;
            String resolved = cmd.replace("%p", player.getGameProfile().name())
                    .replace("%u", player.getUUID().toString());
            String mode = data.mode != null ? data.mode : (data.asPlayer ? "player" : "console");

            if (mode.equals("say")) {
                // NPC-branded chat line, ONLY to the clicking player
                String name = String.valueOf(M_GET_FULL_NAME.invoke(npc));
                player.sendSystemMessage(Component.literal("\u00a77<\u00a7r" + name + "\u00a77>\u00a7r \u00a7f" + resolved));
                return;
            }

            Object clicker = player.getBukkitEntity();
            Object sender = mode.equals("player") ? clicker : org.bukkit.Bukkit.getConsoleSender();
            LOGGER.info("[Npc] {} clicked NPC {} -> /{}", player.getGameProfile().name(), id, resolved);
            org.bukkit.Bukkit.dispatchCommand((org.bukkit.command.CommandSender) sender, resolved);
        } catch (Exception e) {
            LOGGER.warn("[Npc] click dispatch failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ //
    //  Tick driver (prune stale bindings once Citizens is up)
    // ------------------------------------------------------------------ //

    public static void tick(MinecraftServer server) {
        if (!ensureBridge() || prunedOnce) return;
        prunedOnce = true;
        Set<Integer> live = new HashSet<>();
        try {
            for (Object npc : (Iterable<?>) REGISTRY) {
                live.add((Integer) M_GET_ID.invoke(npc));
            }
        } catch (Exception e) {
            return;
        }
        boolean changed = BINDINGS.keySet().removeIf(id -> !live.contains(id));
        if (changed) save();
    }

    // ------------------------------------------------------------------ //
    //  Subcommand implementations
    // ------------------------------------------------------------------ //

    private static int spawnHere(CommandSourceStack source, String name, String type) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("\u00a7cUse 'osnpc spawnat' from console."));
            return 0;
        }
        ServerLevel level = player.level();
        spawnAt(source, level, player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), name, type);
        return 1;
    }

    private static int spawnAt(CommandSourceStack source, String worldName, String xs, String ys,
                                String zs, String yaws, String pitches, String name, String type) {
        ServerLevel level = findLevel(source, worldName);
        if (level == null) return 0;
        Double x = parseCoord(xs), y = parseCoord(ys), z = parseCoord(zs);
        Float yaw = parseFloat(yaws), pitch = parseFloat(pitches);
        if (x == null || y == null || z == null || yaw == null || pitch == null) {
            source.sendFailure(Component.literal("\u00a7cExpected numbers: x y z yaw pitch."));
            return 0;
        }
        spawnAt(source, level, x, y, z, yaw, pitch, name, type);
        return 1;
    }

    private static int spawnAt(CommandSourceStack source, ServerLevel level, double x, double y, double z,
                                float yaw, float pitch, String name, String type) {
        if (!ensureBridge()) {
            source.sendFailure(Component.literal("\u00a7cCitizens plugin is not installed/enabled."));
            return 0;
        }
        if (name == null || name.isBlank()) {
            source.sendFailure(Component.literal("\u00a7cNPC name required."));
            return 0;
        }
        org.bukkit.World world = level.getWorld();
        org.bukkit.Location loc = new org.bukkit.Location(world, x, y, z, yaw, pitch);
        Consumer<Object> spawner = npc -> {
            try {
                M_SPAWN.invoke(npc, loc);
                int id = (Integer) M_GET_ID.invoke(npc);
                String fullName = String.valueOf(M_GET_FULL_NAME.invoke(npc));
                source.sendSuccess(() -> Component.literal("\u00a7aSpawned NPC \u00a7f" + fullName
                        + " \u00a7a(id \u00a7e" + id + "\u00a7a). Bind with: \u00a7f/osnpc cmd " + id + " <command>"), false);
                LOGGER.info("[Npc] {} spawned NPC {} ({}) at {},{},{}/{} {} in {}",
                        source.getTextName(), id, fullName, (int) x, (int) y, (int) z, (int) yaw, (int) pitch,
                        level.dimension().identifier());
            } catch (Exception e) {
                source.sendFailure(Component.literal("\u00a7cSpawn failed: " + e));
                LOGGER.warn("[Npc] spawn failed: {}", e.toString());
            }
        };
        // Load the chunk first — "virtually anywhere" includes unloaded terrain
        // and other dimensions. getChunkAtAsync completes on the main thread.
        world.getChunkAtAsync(((int) x) >> 4, ((int) z) >> 4).thenAccept(v -> {
            try {
                Object npc = M_CREATE.invoke(REGISTRY,
                        org.bukkit.entity.EntityType.valueOf(type.toUpperCase(Locale.ROOT)),
                        legacyName(name));
                spawner.accept(npc);
            } catch (Exception e) {
                source.sendFailure(Component.literal("\u00a7cUnknown entity type '" + type + "' — try 'player'."));
            }
        });
        return 1;
    }

    private static int bindCommand(int id, String command, boolean asPlayer) {
        NpcData data = BINDINGS.computeIfAbsent(id, k -> new NpcData());
        data.command = command;
        data.asPlayer = asPlayer;
        data.mode = asPlayer ? "player" : "console";
        save();
        LOGGER.info("[Npc] bound NPC {} -> {} /{}", id, asPlayer ? "player" : "console", command);
        return 1;
    }

    private static int bindSay(int id, String message) {
        NpcData data = BINDINGS.computeIfAbsent(id, k -> new NpcData());
        data.command = message;
        data.mode = "say";
        data.asPlayer = false;
        save();
        LOGGER.info("[Npc] bound NPC {} -> say: {}", id, message);
        return 1;
    }

    private static int setCooldown(int id, int seconds) {
        NpcData data = BINDINGS.computeIfAbsent(id, k -> new NpcData());
        data.cooldownSeconds = seconds;
        save();
        return 1;
    }

    private static int rotate(CommandSourceStack source, int id, String yaws, String pitches) {
        Object npc = requireNpc(source, id);
        if (npc == null) return 0;
        Float yaw = parseFloat(yaws), pitch = parseFloat(pitches);
        if (yaw == null || pitch == null) {
            source.sendFailure(Component.literal("\u00a7cExpected numbers: yaw [pitch]."));
            return 0;
        }
        try {
            org.bukkit.Location loc = npcLoc(npc);
            loc.setYaw(yaw % 360.0f);
            loc.setPitch(pitch % 360.0f);
            place(npc, loc);
            source.sendSuccess(() -> Component.literal("\u00a7aRotated NPC \u00a7e" + id + "\u00a7a."), false);
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cRotate failed: " + e));
        }
        return 1;
    }

    private static int faceMe(CommandSourceStack source, int id) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("\u00a7cIn-game only."));
            return 0;
        }
        Object npc = requireNpc(source, id);
        if (npc == null) return 0;
        try {
            place(npc, npcLoc(npc));
            M_FACE_LOCATION.invoke(npc, ((org.bukkit.entity.Entity) player.getBukkitEntity()).getLocation());
            source.sendSuccess(() -> Component.literal("\u00a7aNPC \u00a7e" + id + "\u00a7a now faces you."), false);
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cFace failed: " + e));
        }
        return 1;
    }

    private static int lookClose(CommandSourceStack source, int id, boolean on) {
        Object npc = requireNpc(source, id);
        if (npc == null) return 0;
        try {
            Object trait = M_GET_OR_ADD_TRAIT.invoke(npc, LOOKCLOSE_CLASS);
            M_LOOK_ENABLE.invoke(trait, on);
            source.sendSuccess(() -> Component.literal("\u00a7aNPC \u00a7e" + id + "\u00a7a look-close "
                    + (on ? "\u00a7aON" : "\u00a7cOFF") + "\u00a7a."), false);
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cLook-close failed: " + e));
        }
        return 1;
    }

    private static int lookCloseHead(CommandSourceStack source, int id, boolean headOnly) {
        Object npc = requireNpc(source, id);
        if (npc == null) return 0;
        try {
            Object trait = M_GET_OR_ADD_TRAIT.invoke(npc, LOOKCLOSE_CLASS);
            M_LOOK_HEAD_ONLY.invoke(trait, headOnly);
            M_LOOK_ENABLE.invoke(trait, true);
            source.sendSuccess(() -> Component.literal("\u00a7aNPC \u00a7e" + id + "\u00a7a look-close head-only "
                    + (headOnly ? "\u00a7aON" : "\u00a7cOFF") + "\u00a7a."), false);
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cLook-close failed: " + e));
        }
        return 1;
    }

    private static int moveTo(CommandSourceStack source, int id, String xs, String ys, String zs, String worldName) {
        Object npc = requireNpc(source, id);
        if (npc == null) return 0;
        try {
            ServerPlayer player = source.getPlayer();
            org.bukkit.Location loc;
            if (xs == null) {
                if (player == null) {
                    source.sendFailure(Component.literal("\u00a7cConsole must give coords: move <id> <x> <y> <z> [world]."));
                    return 0;
                }
                loc = ((org.bukkit.entity.Entity) player.getBukkitEntity()).getLocation();
            } else {
                Double x = parseCoord(xs), y = parseCoord(ys), z = parseCoord(zs);
                if (x == null || y == null || z == null) {
                    source.sendFailure(Component.literal("\u00a7cExpected numbers: x y z."));
                    return 0;
                }
                ServerLevel level;
                if (worldName != null) {
                    level = findLevel(source, worldName);
                } else {
                    String curWorld = npcLoc(npc).getWorld().getName();
                    level = findLevelByName(curWorld);
                }
                if (level == null) {
                    source.sendFailure(Component.literal("\u00a7cCould not resolve NPC's world."));
                    return 0;
                }
                loc = new org.bukkit.Location(level.getWorld(), x, y, z);
            }
            org.bukkit.World world = loc.getWorld();
            world.getChunkAtAsync(((int) loc.getX()) >> 4, ((int) loc.getZ()) >> 4).thenAccept(v -> {
                try {
                    place(npc, loc);
                    source.sendSuccess(() -> Component.literal("\u00a7aMoved NPC \u00a7e" + id + "\u00a7a."), false);
                } catch (Exception e) {
                    source.sendFailure(Component.literal("\u00a7cMove failed: " + e));
                }
            });
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cMove failed: " + e));
        }
        return 1;
    }

    private static int info(CommandSourceStack source, int id) {
        Object npc = requireNpc(source, id);
        if (npc == null) return 0;
        try {
            NpcData data = BINDINGS.get(id);
            String fullName = String.valueOf(M_GET_FULL_NAME.invoke(npc));
            String locStr;
            try {
                org.bukkit.Location loc = npcLoc(npc);
                locStr = loc.getWorld().getName() + " " + loc.getBlockX() + "," + loc.getBlockY() + ","
                        + loc.getBlockZ() + " yaw=" + (int) loc.getYaw()
                        + (npcSpawned(npc) ? "" : " (chunk unloaded)");
            } catch (Exception e) {
                locStr = "unknown";
            }
            final String locOut = locStr;
            source.sendSuccess(() -> Component.literal("\u00a76NPC \u00a7e" + id + " \u00a76\"" + fullName + "\""
                    + "\u00a77 loc=" + locOut
                    + "\u00a77 cmd=" + (data == null || data.command == null ? "(none)" :
                        (data.mode != null && data.mode.equals("say") ? "say: "
                         : data.asPlayer ? "as-player: " : "console: ") + data.command
                        + (data.cooldownSeconds > 0 ? " (cooldown " + data.cooldownSeconds + "s)" : ""))
                    + "\u00a77"), false);
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cInfo failed: " + e));
        }
        return 1;
    }

    private static int list(CommandSourceStack source) {
        if (!ensureBridge()) {
            source.sendFailure(Component.literal("\u00a7cCitizens plugin is not installed/enabled."));
            return 0;
        }
        try {
            StringBuilder sb = new StringBuilder("\u00a76Command NPCs:\u00a7r");
            int count = 0;
            for (Object npc : (Iterable<?>) REGISTRY) {
                int id = (Integer) M_GET_ID.invoke(npc);
                NpcData data = BINDINGS.get(id);
                if (data == null) continue;
                count++;
                sb.append("\n\u00a7e").append(id).append("\u00a7f \"").append(M_GET_FULL_NAME.invoke(npc))
                        .append("\" \u00a77-> ").append(data.asPlayer ? "as-player: " : "console: ")
                        .append(data.command == null ? "(none)" : data.command);
            }
            if (count == 0) sb.append(" \u00a77none bound yet — use /osnpc cmd");
            final String out = sb.toString();
            source.sendSuccess(() -> Component.literal(out), false);
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cList failed: " + e));
        }
        return 1;
    }

    private static int remove(CommandSourceStack source, int id) {
        if (!ensureBridge()) return 0;
        try {
            Object npc = M_GET_BY_ID.invoke(REGISTRY, id);
            if (npc == null) {
                source.sendFailure(Component.literal("\u00a7cNo NPC with id " + id + "."));
                return 0;
            }
            String name = String.valueOf(M_GET_FULL_NAME.invoke(npc));
            M_DESTROY.invoke(npc);
            BINDINGS.remove(id);
            save();
            source.sendSuccess(() -> Component.literal("\u00a7aRemoved NPC \u00a7e" + id + "\u00a7a \"" + name + "\"."), false);
            LOGGER.info("[Npc] {} removed NPC {} ({})", source.getTextName(), id, name);
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cRemove failed: " + e));
        }
        return 1;
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    private static Object requireNpc(CommandSourceStack source, int id) {
        if (!ensureBridge()) {
            source.sendFailure(Component.literal("\u00a7cCitizens plugin is not installed/enabled."));
            return null;
        }
        try {
            Object npc = M_GET_BY_ID.invoke(REGISTRY, id);
            if (npc == null) source.sendFailure(Component.literal("\u00a7cNo NPC with id " + id + " — try /osnpc list."));
            return npc;
        } catch (Exception e) {
            source.sendFailure(Component.literal("\u00a7cLookup failed: " + e));
            return null;
        }
    }

    private static ServerLevel findLevelByName(String name) {
        for (ServerLevel lvl : MinecraftServer.getServer().getAllLevels()) {
            if (lvl.getWorld().getName().equalsIgnoreCase(name)) return lvl;
        }
        return null;
    }

    private static ServerLevel findLevel(CommandSourceStack source, String name) {
        MinecraftServer server = MinecraftServer.getServer();
        for (ServerLevel lvl : server.getAllLevels()) {
            if (lvl.getWorld().getName().equalsIgnoreCase(name)) return lvl;
        }
        source.sendFailure(Component.literal("\u00a7cUnknown world '" + name + "'."));
        return null;
    }

    private static Double parseCoord(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return null; }
    }

    private static Float parseFloat(String s) {
        try { return Float.parseFloat(s); } catch (Exception e) { return null; }
    }

    /** Translates &-codes and keeps the string under the name length limit. */
    private static String legacyName(String name) {
        String translated = name.replace('&', '\u00a7');
        if (translated.length() > 44) translated = translated.substring(0, 44);
        return translated;
    }


    /** True when the Bukkit entity is a Citizens NPC (fake player). */
    public static boolean isCitizensNpc(Object bukkitEntity) {
        if (bukkitEntity == null) return false;
        try {
            if (!ensureBridge()) return false;
            return (Boolean) M_IS_NPC.invoke(REGISTRY, bukkitEntity);
        } catch (Exception e) {
            return false;
        }
    }

    /** NPCs despawn when their chunk unloads (no players nearby); fall back to
     *  the stored location so rotate/move/info still work on unspawned NPCs. */
    private static org.bukkit.Location npcLoc(Object npc) throws Exception {
        if (npcSpawned(npc)) {
            return ((org.bukkit.entity.Entity) M_GET_ENTITY.invoke(npc)).getLocation();
        }
        return (org.bukkit.Location) M_GET_STORED_LOC.invoke(npc);
    }

    /** Teleport when spawned; (re)spawn at the target when the chunk is unloaded. */
    private static void place(Object npc, org.bukkit.Location loc) throws Exception {
        if (npcSpawned(npc)) {
            M_TELEPORT.invoke(npc, loc, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        } else {
            M_SPAWN.invoke(npc, loc);
        }
    }

    private static boolean npcSpawned(Object npc) {
        try { return (Boolean) M_IS_SPAWNED.invoke(npc); } catch (Exception e) { return false; }
    }

    /** Lazily resolves the Citizens bridge; returns true when usable.
     *  Plugin classes are NOT visible from the server classloader — every
     *  Citizens class must be loaded through the plugin's own classloader.
     *  A failed resolve is retried periodically (plugin may load later). */
    // --- Bridge surface for OsmiumBaltop rank NPCs (same package) ---

    static boolean bridgeReady() { return ensureBridge(); }

    /** Creates + spawns an NPC at loc; returns the Citizens NPC or null. */
    static Object createNpc(String typeName, String name, org.bukkit.Location loc) {
        if (!ensureBridge()) return null;
        try {
            Object npc = M_CREATE.invoke(REGISTRY,
                    org.bukkit.entity.EntityType.valueOf(typeName.toUpperCase(Locale.ROOT)),
                    legacyName(name));
            M_SPAWN.invoke(npc, loc);
            return npc;
        } catch (Exception e) {
            LOGGER.warn("[Npc] bridge createNpc failed: {}", e.toString());
            return null;
        }
    }

    static Object npcById(int id) {
        try { return M_GET_BY_ID.invoke(REGISTRY, id); } catch (Exception e) { return null; }
    }

    static int npcId(Object npc) {
        try { return (Integer) M_GET_ID.invoke(npc); } catch (Exception e) { return -1; }
    }

    static void npcSetName(Object npc, String name) {
        try { NPC_CLASS.getMethod("setName", String.class).invoke(npc, legacyName(name)); }
        catch (Exception e) { LOGGER.warn("[Npc] setName failed: {}", e.toString()); }
    }

    /** Applies a player skin (Citizens fetches it async from Mojang). */
    static void npcSetSkin(Object npc, String skinName) {
        try {
            Object trait = M_GET_OR_ADD_TRAIT.invoke(npc, SKIN_CLASS);
            M_SKIN_SET.invoke(trait, skinName, true);
        } catch (Exception e) {
            LOGGER.warn("[Npc] setSkin failed: {}", e.toString());
        }
    }

    static void npcDestroy(Object npc) {
        try { M_DESTROY.invoke(npc); } catch (Exception e) { LOGGER.warn("[Npc] destroy failed: {}", e.toString()); }
    }

    static boolean npcSpawnedPkg(Object npc) { return npcSpawned(npc); }

    private static boolean ensureBridge() {
        if (available) return true;
        if (initialized && System.currentTimeMillis() - lastAttempt < 10_000) return false;
        synchronized (OsmiumNpc.class) {
            if (available) return true;
            if (initialized && System.currentTimeMillis() - lastAttempt < 10_000) return false;
            initialized = true;
            lastAttempt = System.currentTimeMillis();
            try {
                org.bukkit.plugin.Plugin pl = org.bukkit.Bukkit.getPluginManager().getPlugin("Citizens");
                if (pl == null || !pl.isEnabled()) return false;
                ClassLoader cl = pl.getClass().getClassLoader();
                Class<?> api = cl.loadClass("net.citizensnpcs.api.CitizensAPI");
                REGISTRY = api.getMethod("getNPCRegistry").invoke(null);
                Class<?> registryClass = cl.loadClass("net.citizensnpcs.api.npc.NPCRegistry");
                NPC_CLASS = cl.loadClass("net.citizensnpcs.api.npc.NPC");
                LOOKCLOSE_CLASS = cl.loadClass("net.citizensnpcs.trait.LookClose");
                SKIN_CLASS = cl.loadClass("net.citizensnpcs.trait.SkinTrait");
                M_CREATE = registryClass.getMethod("createNPC",
                        org.bukkit.entity.EntityType.class, String.class);
                M_GET_BY_ID = registryClass.getMethod("getById", int.class);
                M_IS_NPC = registryClass.getMethod("isNPC", org.bukkit.entity.Entity.class);
                M_GET_NPC = registryClass.getMethod("getNPC", org.bukkit.entity.Entity.class);
                M_SPAWN = NPC_CLASS.getMethod("spawn", org.bukkit.Location.class);
                M_IS_SPAWNED = NPC_CLASS.getMethod("isSpawned");
                M_GET_ID = NPC_CLASS.getMethod("getId");
                M_GET_ENTITY = NPC_CLASS.getMethod("getEntity");
                M_FACE_LOCATION = NPC_CLASS.getMethod("faceLocation", org.bukkit.Location.class);
                M_TELEPORT = NPC_CLASS.getMethod("teleport", org.bukkit.Location.class,
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class);
                M_GET_OR_ADD_TRAIT = NPC_CLASS.getMethod("getOrAddTrait", Class.class);
                M_LOOK_ENABLE = LOOKCLOSE_CLASS.getMethod("lookClose", boolean.class); // Citizens 2.0.44 (was setEnabled pre-2.0.31)
                M_LOOK_HEAD_ONLY = LOOKCLOSE_CLASS.getMethod("setHeadOnly", boolean.class);
                M_GET_FULL_NAME = NPC_CLASS.getMethod("getFullName");
                M_DESTROY = NPC_CLASS.getMethod("destroy");
                M_GET_STORED_LOC = NPC_CLASS.getMethod("getStoredLocation");
                M_SKIN_SET = SKIN_CLASS.getMethod("setSkinName", String.class, boolean.class);
                available = true;
                LOGGER.info("[Npc] Citizens bridge ready");
            } catch (Exception e) {
                LOGGER.info("[Npc] Citizens unavailable: {}", e.toString());
            }
            return available;
        }
    }

    private static volatile long lastAttempt;

    // ------------------------------------------------------------------ //
    //  Persistence
    // ------------------------------------------------------------------ //

    private static void load() {
        try {
            if (!dataFile.exists()) return;
            Store raw = GSON.fromJson(java.nio.file.Files.readString(dataFile.toPath()), Store.class);
            if (raw == null || raw.npcs == null) return;
            Map<String, Map<String, Object>> npcs = raw.npcs;
            for (Map.Entry<String, Map<String, Object>> e : npcs.entrySet()) {
                try {
                    Map<String, Object> entry = (Map<String, Object>) e.getValue();
                    NpcData data = new NpcData();
                    data.command = (String) entry.get("command");
                    data.asPlayer = Boolean.TRUE.equals(entry.get("asPlayer"));
                    data.cooldownSeconds = entry.get("cooldown") instanceof Number n ? n.intValue() : 0;
                    data.mode = (String) entry.get("mode");
                    BINDINGS.put(Integer.parseInt(e.getKey()), data);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            LOGGER.warn("[Npc] failed to load osmium-npcs.json: {}", e.toString());
        }
    }

    private static void save() {
        try {
            Store root = new Store();
            root.version = 1;
            root.npcs = new TreeMap<>();
            for (Map.Entry<Integer, NpcData> e : BINDINGS.entrySet()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("command", e.getValue().command);
                entry.put("asPlayer", e.getValue().asPlayer);
                entry.put("cooldown", e.getValue().cooldownSeconds);
                entry.put("mode", e.getValue().mode);
                root.npcs.put(String.valueOf(e.getKey()), entry);
            }
            java.nio.file.Files.writeString(dataFile.toPath(), GSON.toJson(root));
        } catch (Exception e) {
            LOGGER.warn("[Npc] failed to save osmium-npcs.json: {}", e.toString());
        }
    }
}
