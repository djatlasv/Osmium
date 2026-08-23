package org.osmium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * /sethome [name], /home [name], /homes, /spawn
 *
 * Homes persist to osmium-homes.json. Teleports use the same countdown +
 * move-cancel pattern as /rtp and /tpa.
 */
public class OsmiumHomes {

    private record HomePos(String dim, double x, double y, double z, float yaw, float pitch) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, Map<String, HomePos>>>() {}.getType();

    private static final Map<UUID, Map<String, HomePos>> HOMES = new HashMap<>();

    private record PendingHome(UUID playerUuid, HomePos target, int teleportAtTick,
                               double startX, double startY, double startZ) {}

    private static final Map<UUID, PendingHome> PENDING = new HashMap<>();
    private static File dataFile;

    public static void init(File serverDir) {
        dataFile = new File(serverDir, "osmium-homes.json");
        load();
        debug("homes loaded for " + HOMES.size() + " players");
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sethome")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (!enabled()) return 0;
                    setHome(p, "home");
                    return 1;
                })
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            if (!enabled()) return 0;
                            setHome(p, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT));
                            return 1;
                        })));

        dispatcher.register(Commands.literal("home")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (!enabled()) return 0;
                    goHome(p, "home");
                    return 1;
                })
                .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            if (!enabled()) return 0;
                            goHome(p, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT));
                            return 1;
                        })));

        dispatcher.register(Commands.literal("homes")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (!enabled()) return 0;
                    listHomes(p);
                    return 1;
                }));

        dispatcher.register(Commands.literal("spawn")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    if (!enabled()) return 0;
                    goToSpawn(p);
                    return 1;
                }));
    }

    private static boolean enabled() { return OsmiumConfig.tpaEnabled || OsmiumConfig.homesEnabled; }

    private static void debug(String msg) {
        if (OsmiumConfig.homesDebug) {
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Homes").info("[DEBUG] {}", msg);
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private static int maxHomes() {
        return Math.max(1, OsmiumConfig.homesMaxPerPlayer);
    }

    private static void setHome(ServerPlayer player, String name) {
        var pos = player.position();
        HomePos home = new HomePos(
                player.level().dimension().identifier().toString(),
                pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());

        Map<String, HomePos> homes = HOMES.computeIfAbsent(player.getUUID(), k -> new LinkedHashMap<>());
        boolean replace = homes.containsKey(name);
        if (!replace && homes.size() >= maxHomes()) {
            player.sendSystemMessage(Component.literal(ChatFormatting.RED + "You already have "
                    + maxHomes() + " homes! Use /homes to see them."));
            return;
        }
        homes.put(name, home);
        save();

        player.sendSystemMessage(Component.literal(ChatFormatting.GREEN + (replace ? "Home '" : "Home '") + name
                + "' set here." + (replace ? "" : ChatFormatting.GRAY + " (" + homes.size() + "/" + maxHomes() + ")")));
        debug(player.getGameProfile().name() + " set home '" + name + "'");
    }

    private static void goHome(ServerPlayer player, String name) {
        Map<String, HomePos> homes = HOMES.get(player.getUUID());
        HomePos home = homes != null ? homes.get(name) : null;
        if (home == null) {
            player.sendSystemMessage(Component.literal(ChatFormatting.RED + "No home named '" + name + "'."));
            return;
        }
        startCountdown(player, home, "'" + name + "'");
    }

    private static void listHomes(ServerPlayer player) {
        Map<String, HomePos> homes = HOMES.get(player.getUUID());
        if (homes == null || homes.isEmpty()) {
            player.sendSystemMessage(Component.literal(ChatFormatting.YELLOW + "No homes set. Use /sethome."));
            return;
        }
        StringBuilder sb = new StringBuilder(ChatFormatting.GREEN.toString() + ChatFormatting.BOLD + "Homes:\n"
                + ChatFormatting.RESET);
        for (String name : homes.keySet()) {
            sb.append(ChatFormatting.GRAY).append("• ").append(ChatFormatting.WHITE).append("/home ").append(name).append("\n");
        }
        player.sendSystemMessage(Component.literal(sb.toString()));
    }

    private static void goToSpawn(ServerPlayer player) {
        var respawn = ((net.minecraft.world.level.storage.PrimaryLevelData)
                player.level().getServer().getWorldData()).getRespawnData();
        var spawnPos = respawn.pos();
        // Resolve the spawn dimension's actual ServerLevel
        ServerLevel level = player.level().getServer().getLevel(respawn.dimension());
        if (level == null) level = player.level().getServer().overworld();
        HomePos home = new HomePos(level.dimension().identifier().toString(),
                spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                respawn.yaw(), respawn.pitch());
        startCountdown(player, home, "spawn");
    }

    // ------------------------------------------------------------------
    // Countdown + teleport (same pattern as /tpa)
    // ------------------------------------------------------------------

    private static void startCountdown(ServerPlayer player, HomePos target, String label) {
        UUID uuid = player.getUUID();
        if (PENDING.containsKey(uuid)) {
            player.sendSystemMessage(Component.literal(ChatFormatting.RED + "You already have a teleport in progress!"));
            return;
        }
        net.minecraft.server.MinecraftServer server = player.level().getServer();
        if (server == null) return;

        int delayTicks = Math.max(0, OsmiumConfig.homesDelaySeconds) * 20;
        int at = server.getTickCount() + delayTicks;
        PENDING.put(uuid, new PendingHome(uuid, target, at,
                player.getX(), player.getY(), player.getZ()));

        if (delayTicks > 0) {
            player.sendSystemMessage(Component.literal(
                    ChatFormatting.YELLOW + "Teleporting to " + label + " in "
                            + OsmiumConfig.homesDelaySeconds + "s... Don't move!"));
        }
        debug(player.getGameProfile().name() + " pending teleport to " + label);
    }

    /** Tick driver — called from MinecraftServer.tickChildren (main thread). */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        long nowTick = server.getTickCount();
        Iterator<Map.Entry<UUID, PendingHome>> it = PENDING.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, PendingHome> e = it.next();
            PendingHome ph = e.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(ph.playerUuid());
            if (player == null) { it.remove(); continue; }

            double dx = player.getX() - ph.startX();
            double dy = player.getY() - ph.startY();
            double dz = player.getZ() - ph.startZ();
            if (dx * dx + dy * dy + dz * dz > 4.0) {
                it.remove();
                player.sendSystemMessage(Component.literal(ChatFormatting.RED + "Teleport cancelled — you moved!"));
                continue;
            }

            if (nowTick < ph.teleportAtTick()) {
                long left = ph.teleportAtTick() - nowTick;
                if (left % 20 == 0) {
                    long s = left / 20;
                    if (s > 0 && s <= 3) {
                        player.sendSystemMessage(Component.literal(
                                ChatFormatting.YELLOW + "Teleporting in " + ChatFormatting.WHITE + s + "..."));
                    }
                }
                continue;
            }

            it.remove();
            executeTeleport(player, ph.target());
        }
    }

    private static void executeTeleport(ServerPlayer player, HomePos home) {
        net.minecraft.server.MinecraftServer server = player.level().getServer();
        if (server == null) return;
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.Identifier.withDefaultNamespace(home.dim())));
        if (level == null) {
            player.sendSystemMessage(Component.literal(ChatFormatting.RED + "That dimension is not loaded."));
            return;
        }
        player.teleportTo(level, home.x(), home.y(), home.z(), Set.of(),
                home.yaw(), home.pitch(), true,
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND);
        player.sendSystemMessage(Component.literal(ChatFormatting.GREEN + "Teleported!"));
    }

    public static void cancel(UUID playerUuid) {
        PENDING.remove(playerUuid);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private static void load() {
        if (dataFile == null || !dataFile.exists()) return;
        try (var reader = new java.io.InputStreamReader(new java.io.FileInputStream(dataFile), StandardCharsets.UTF_8)) {
            Map<String, Map<String, HomePos>> loaded = GSON.fromJson(reader, TYPE);
            if (loaded != null) {
                for (Map.Entry<String, Map<String, HomePos>> e2 : loaded.entrySet()) {
                    try { HOMES.put(UUID.fromString(e2.getKey()), e2.getValue()); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Homes")
                    .error("Failed to load osmium-homes.json", e);
        }
    }

    private static void save() {
        if (dataFile == null) return;
        try (var writer = new java.io.OutputStreamWriter(new java.io.FileOutputStream(dataFile), StandardCharsets.UTF_8)) {
            GSON.toJson(HOMES, writer);
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Homes")
                    .error("Failed to save osmium-homes.json: {}", e.getMessage());
        }
    }
}
