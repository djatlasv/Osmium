# Osmium

Custom Purpur 1.21.11 fork for semi-vanilla SMPs with native anticheat and security features baked in at the server level.

## Build & Test

```bash
./gradlew build -x test          # Build the server jar
./gradlew applyPatches            # Apply all patches (run after cloning or pulling)
```

## Patch Workflow (CRITICAL)

This repo has **three nested git repos**:

| Repo | Path | Contains |
|------|------|----------|
| Root | `~/Osmium` | Gradle config, patch files, Osmium source under `purpur-server/src/main/java/org/osmium/` |
| NMS | `~/Osmium/purpur-server/src/minecraft` | Decompiled Minecraft server source (net.minecraft.*) |
| CraftBukkit | `~/Osmium/paper-server` | CraftBukkit source (org.bukkit.craftbukkit.*) |

**When editing NMS or CraftBukkit files:**

1. Commit changes into the **nested** repo (not root)
2. Rebuild patches from root:
   ```bash
   ./gradlew rebuildServerPatches && ./gradlew rebuildPaperServerPatches && ./gradlew rebuildMinecraftPatches
   ```
3. Commit the updated patch files to the **root** repo

**Osmium's own source** (`purpur-server/src/main/java/org/osmium/`) is committed directly to the root repo — no patching needed.

## Project Structure

```
purpur-server/src/main/java/org/osmium/
├── OsmiumConfig.java                      # Config loader — generates osmium.yml
└── anticheat/
    ├── OsmiumChunkProcessor.java          # Chunk hiding (anti-xray extension)
    ├── OsmiumChunkPacketInfo.java          # Carries ServerPlayer through packet pipeline
    ├── OsmiumAltTracker.java              # IP-to-UUID tracking for alt detection
    ├── OsmiumChatFilter.java              # Offline chat filter with word list
    └── OsmiumBrandEnforcement.java        # Native HandShaker protocol — mod list enforcement
```

### Key injection points (NMS / CraftBukkit)

- `purpur-server/src/minecraft/java/net/minecraft/world/level/Level.java` ~line 915 — OsmiumChunkProcessor replaces Paper's anti-xray controller
- `purpur-server/src/minecraft/java/net/minecraft/server/dedicated/DedicatedServer.java` ~line 265 — OsmiumConfig.init() + OsmiumAltTracker.init()
- `purpur-server/src/minecraft/java/net/minecraft/server/network/ServerCommonPacketListenerImpl.java` handleCustomPayload() — intercepts `hand-shaker:mods` channel
- `purpur-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java` markClientLoaded() — schedules brand check; onDisconnect() — cleanup
- `purpur-server/src/minecraft/java/net/minecraft/server/MinecraftServer.java` tickChildren() — processes brand enforcement tick queue
- `paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java` ~line 1009 — OsmiumConfig.init() after PurpurConfig
- `paper-server/src/main/java/org/bukkit/craftbukkit/Main.java` — `--osmium-settings` CLI option

## Config (osmium.yml)

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `chunk-hiding.enabled` | bool | `true` | Master toggle for chunk hiding |
| `chunk-hiding.y-threshold` | int | `0` | Y level below which ores/containers are hidden |
| `chunk-hiding.proximity-radius` | int | `32` | Blocks — skip hiding if player is this close |
| `y-level-hiding.enabled` | bool | `false` | Replace ALL blocks below threshold |
| `y-level-hiding.threshold` | int | `-32` | Y level for blanket block replacement |
| `brand-enforcement.enabled` | bool | `false` | Master toggle for brand enforcement |
| `brand-enforcement.mode` | string | `"vanilla"` | `strict` (require HandShaker) or `vanilla` (allow vanilla clients) |
| `brand-enforcement.kick-message` | string | *(see code)* | Message shown on kick — supports `{mods}` placeholder |
| `brand-enforcement.check-delay-ticks` | int | `100` | Ticks to wait for HandShaker payload before checking |
| `brand-enforcement.required-mods` | list | `[]` | Mod IDs that must be present |
| `brand-enforcement.blacklisted-mods` | list | `[]` | Mod IDs that trigger a kick |
| `alt-ban.enabled` | bool | `false` | Auto-ban alts sharing IP with banned players |
| `alt-ban.kick-message` | string | `"You are banned (alt account detected)."` | Message shown on alt kick |
| `chat-filter.enabled` | bool | `false` | Master toggle for chat filter |
| `chat-filter.action` | string | `"block"` | Action on match: block, kick, or mute |
| `chat-filter.message` | string | *(see code)* | Message shown to player |

### Adding a new config key

1. Add a `public static` field with default value in `OsmiumConfig.java`
2. Add a `private static void` method (same section pattern as existing ones) that calls `getBoolean`/`getInt`/`getString`
3. The reflective `readConfig()` auto-discovers private void methods — no registration needed

## Architecture Notes

- **OsmiumChunkProcessor** wraps Paper's anti-xray as a delegate — it runs Paper's logic first, then applies Osmium's hiding passes on top. Three passes: anti-xray delegate, deepslate/container hiding, y-level hiding.
- **OsmiumChunkPacketInfo** uses `ThreadLocal<ServerPlayer>` to thread the player reference through the packet pipeline where the API doesn't pass it directly.
- **OsmiumAltTracker** persists IP-to-UUID mappings to `osmium-ips.json` using Gson. ConcurrentHashMap for thread safety. Called from PlayerList on join to record associations and check bans.
- **OsmiumChatFilter** loads word list from `osmium-words.json`. Supports exact (case-insensitive) and `regex:` prefixed patterns.
- **OsmiumBrandEnforcement** intercepts `hand-shaker:mods` plugin channel at the NMS level, decodes VarInt-prefixed mod list + SHA-256 hash, tracks per-player state in ConcurrentHashMaps, and runs delayed checks via a tick queue in MinecraftServer. Fixes the upstream HandShaker vanilla-mode bug where vanilla clients bypassed all mod checks.

## Roadmap

- GrimAC integration (embedded module, not plugin)

## Environment

- Java 21 (toolchain enforced in build.gradle.kts)
- Arch Linux dev environment
- SSH auth to GitHub
