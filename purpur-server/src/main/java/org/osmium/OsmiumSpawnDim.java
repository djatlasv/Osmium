package org.osmium;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional dedicated spawn dimension ("osmium:spawn").
 *
 * The dimension itself ships as a bundled mini-datapack (flat grass, always
 * day, no hostile spawns) extracted into the overworld's datapacks folder on
 * first boot — vanilla then loads it like any datapack dimension, giving us
 * chunk storage and generation for free. Requires one restart after enabling.
 *
 * Behavior provided natively when enabled:
 *  - Brand-new players are placed in the spawn dimension instead of the
 *    overworld world spawn.
 *  - /spawn teleports there (see OsmiumHomes).
 *  - Nether portals INSIDE the spawn dimension do not link to the Nether —
 *    they drop the player into the Overworld at the same X/Z (the hub exit).
 *  - Dying without a bed/anchor respawns you there.
 */
public final class OsmiumSpawnDim {

    private OsmiumSpawnDim() {}

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("Osmium-SpawnDim");

    private static volatile boolean extracted = false;
    private static volatile boolean extractionFailedLogged = false;

    /** Players awaiting placement into the spawn dimension on their next tick. */
    private static final Set<UUID> PENDING_JOIN = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------
    // Keys & queries
    // ------------------------------------------------------------------

    public static ResourceKey<Level> spawnKey() {
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                Identifier.withDefaultNamespace(OsmiumConfig.spawnDimensionName));
    }

    public static boolean isSpawnDimension(Level level) {
        return level.dimension() == spawnKey();
    }

    public static ServerLevel level(MinecraftServer server) {
        return server.getLevel(spawnKey());
    }

    // ------------------------------------------------------------------
    // Datapack extraction
    // ------------------------------------------------------------------

    /** Bump when the bundled templates change so existing installs get refreshed. */
    private static final int TEMPLATE_VERSION = 2;

    private static void extractDatapack(MinecraftServer server) {
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) return;

            File packsDir = new File(overworld.getWorld().getName(), "datapacks");
            File target = new File(packsDir, "OsmiumSpawn");
            File marker = new File(target, ".osmium");
            String markerContent = "osmium spawn-dimension template v" + TEMPLATE_VERSION + "\n";
            if (marker.exists()
                    && markerContent.equals(Files.readString(marker.toPath(), StandardCharsets.UTF_8))) {
                extracted = true; return; // current version already installed
            }

            target.mkdirs();

            int dpVersion = SharedConstantsMajor();
            String mcmeta = "{\"pack\":{\"pack_format\":" + dpVersion
                    + ",\"min_format\":" + dpVersion
                    + ",\"max_format\":" + dpVersion
                    + ",\"description\":\"Osmium spawn dimension\"}}";
            Files.writeString(new File(target, "pack.mcmeta").toPath(), mcmeta, StandardCharsets.UTF_8);

            writeResource(target, "data/osmium/dimension_type/spawn.json", DIMENSION_TYPE_JSON);
            writeResource(target, "data/osmium/dimension/spawn.json", DIMENSION_JSON);
            Files.writeString(marker.toPath(), markerContent, StandardCharsets.UTF_8);

            extracted = true;
            LOG.info("[Osmium] Spawn dimension datapack installed -> {}/datapacks/OsmiumSpawn. Restart required once.",
                    overworld.getWorld().getName());
        } catch (Exception e) {
            if (!extractionFailedLogged) {
                extractionFailedLogged = true;
                LOG.error("[Osmium] Failed to install spawn-dimension datapack", e);
            }
        }
    }

    private static int SharedConstantsMajor() {
        try {
            var fmt = net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.SERVER_DATA);
            return fmt.major();
        } catch (Exception e) {
            return 48; // sane fallback
        }
    }

    private static void writeResource(File root, String relPath, String content) throws Exception {
        File f = new File(root, relPath.replace('/', File.separatorChar));
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
    }

    private static final String DIMENSION_TYPE_JSON = """
            {
              "ambient_light": 0.0,
              "coordinate_scale": 1.0,
              "default_clock": "minecraft:overworld",
              "has_ceiling": false,
              "has_ender_dragon_fight": false,
              "has_fixed_time": true,
              "has_skylight": true,
              "height": 384,
              "infiniburn": "#minecraft:infiniburn_overworld",
              "logical_height": 384,
              "min_y": -64,
              "monster_spawn_block_light_limit": 0,
              "monster_spawn_light_level": 0,
              "timelines": "#minecraft:in_overworld"
            }
            """;

    private static final String DIMENSION_JSON = """
            {
              "type": "osmium:spawn",
              "generator": {
                "type": "minecraft:flat",
                "settings": {
                  "biome": "minecraft:plains",
                  "features": false,
                  "lakes": false,
                  "layers": [
                    {"block": "minecraft:grass_block", "height": 1},
                    {"block": "minecraft:dirt", "height": 2},
                    {"block": "minecraft:bedrock", "height": 1}
                  ]
                }
              }
            }
            """;

    // ------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------

    /** Called from placeNewPlayer. Marks brand-new players for placement. */
    public static void onPlaceNewPlayer(ServerPlayer player, MinecraftServer server) {
        if (!enabled() || !isLoaded(server)) return;
        if (player.getRespawnConfig() != null) return; // returning player

        // Fresh only: no saved playerdata means they have never played
        if (server.getPlayerList().loadPlayerData(player.nameAndId()).isPresent()) return;

        PENDING_JOIN.add(player.getUUID());
    }

    /**
     * Nether portal redirect: inside the spawn dimension, a nether portal
     * exits into the OVERWORLD at the same X/Z (surface height), 1:1 scale.
     */
    public static TeleportTransition portalRedirect(ServerLevel currentLevel, Entity entity) {
        MinecraftServer server = currentLevel.getServer();
        ServerLevel overworld = server.overworld();
        if (overworld == null) return null;

        int x = (int) Math.floor(entity.getX());
        int z = (int) Math.floor(entity.getZ());
        int y = overworld.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Vec3 pos = new Vec3(x + 0.5, y + 1.0, z + 0.5);

        return new TeleportTransition(
                overworld, pos, Vec3.ZERO, entity.getYRot(), entity.getXRot(),
                false, false, Set.of(), TeleportTransition.DO_NOTHING
        ).withCause(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
    }

    /** Default death respawn inside the spawn dimension. Null when not applicable. */
    public static TeleportTransition respawnTransition(ServerPlayer player,
                                                       TeleportTransition.PostTeleportTransition post) {
        MinecraftServer server = player.level().getServer();
        ServerLevel dim = level(server);
        if (dim == null) return null;
        var rd = dim.getLevelData().getRespawnData();
        BlockPos pos = rd.pos();
        Vec3 spot = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        return new TeleportTransition(dim, spot, Vec3.ZERO, rd.yaw(), rd.pitch(),
                false, false, Set.of(), post);
    }

    /** Preferred /spawn target when the feature is on. Null otherwise. */
    public static ServerLevel spawnTargetLevel(MinecraftServer server) {
        return enabled() ? level(server) : null;
    }

    // ------------------------------------------------------------------
    // Tick driver
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        if (!enabled()) { PENDING_JOIN.clear(); return; }

        if (!extracted) {
            extractDatapack(server);
            return; // wait until installed; dimension appears next restart
        }

        if (PENDING_JOIN.isEmpty()) return;
        ServerLevel dim = level(server);
        if (dim == null) return; // restart still pending

        for (UUID uuid : PENDING_JOIN) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p == null || !p.isAlive()) continue;
            if (isSpawnDimension(p.level())) continue; // already there

            var rd = dim.getLevelData().getRespawnData();
            BlockPos pos = rd.pos();
            p.teleportTo(dim, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    Set.of(), rd.yaw(), rd.pitch(), true,
                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND);
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "\u00a77Welcome! This is the server hub. Step through a portal to reach survival."));
        }
        PENDING_JOIN.clear();
    }

    private static boolean enabled() {
        return OsmiumConfig.spawnDimensionEnabled;
    }

    private static boolean isLoaded(MinecraftServer server) {
        return level(server) != null;
    }

}
