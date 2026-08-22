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
├── OsmiumRtp.java                         # /rtp random teleport GUI with economy support
├── OsmiumScoreboard.java                  # Packet-based sidebar scoreboard with placeholders
├── OsmiumUpdateChecker.java               # Async GitHub commit check on startup
└── anticheat/
    ├── OsmiumChunkProcessor.java          # Chunk hiding (anti-xray extension)
    ├── OsmiumChunkPacketInfo.java          # Carries ServerPlayer through packet pipeline
    ├── OsmiumAltTracker.java              # IP-to-UUID tracking for alt detection
    ├── OsmiumChatFilter.java              # Offline chat filter with word list
    ├── OsmiumBrandEnforcement.java        # Native HandShaker protocol — mod list enforcement
    └── OsmiumDiscordWebhook.java          # Discord webhook notifications (bans, alts, server status)
```

### Key injection points (NMS / CraftBukkit)

- `purpur-server/src/minecraft/java/net/minecraft/world/level/Level.java` ~line 915 — OsmiumChunkProcessor replaces Paper's anti-xray controller
- `purpur-server/src/minecraft/java/net/minecraft/server/dedicated/DedicatedServer.java` ~line 265 — OsmiumConfig.init() + OsmiumAltTracker.init()
- `purpur-server/src/minecraft/java/net/minecraft/server/network/ServerCommonPacketListenerImpl.java` handleCustomPayload() — intercepts `hand-shaker:mods` channel
- `purpur-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java` markClientLoaded() — schedules brand check; onDisconnect() — cleanup
- `purpur-server/src/minecraft/java/net/minecraft/server/MinecraftServer.java` tickChildren() — processes brand enforcement tick queue + RTP countdown + scoreboard tick; initServer() — OsmiumUpdateChecker.checkAsync()
- `purpur-server/src/minecraft/java/net/minecraft/server/players/PlayerList.java` placeNewPlayer() — alt tracker recordJoin + alt ban check + webhook + scoreboard onJoin
- `purpur-server/src/minecraft/java/net/minecraft/commands/Commands.java` command registration — OsmiumRtp.registerCommand() if rtp.enabled
- `purpur-server/src/minecraft/java/net/minecraft/server/network/ServerGamePacketListenerImpl.java` handleContainerClick() — RTP GUI click interception; handleContainerClose() — RTP GUI cleanup; onDisconnect() — RTP state cancel + scoreboard onQuit
- `purpur-server/src/minecraft/java/net/minecraft/server/commands/BanPlayerCommands.java` banPlayers() — webhook on ban
- `purpur-server/src/minecraft/java/net/minecraft/server/dedicated/DedicatedServer.java` initServer()/stopServer() — webhook on start/stop
- `paper-server/src/main/java/org/bukkit/craftbukkit/CraftServer.java` ~line 1009 — OsmiumConfig.init() after PurpurConfig
- `paper-server/src/main/java/org/bukkit/craftbukkit/Main.java` — `--osmium-settings` CLI option

## Config (osmium.yml)

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `chunk-hiding.enabled` | bool | `true` | Master toggle for chunk hiding |
| `chunk-hiding.y-threshold` | int | `0` | Y level below which ores/containers are hidden |
| `chunk-hiding.proximity-radius` | int | `32` | Blocks — skip hiding if player is this close |
| `chunk-hiding.hide-light` | bool | `true` | Zero out light data below threshold to defeat Light Finder hacks |
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
| `rtp.enabled` | bool | `false` | Master toggle for /rtp command |
| `rtp.delay-seconds` | int | `5` | Countdown before teleport |
| `rtp.cost` | double | `0.0` | Economy cost per RTP (Vault required if > 0) |
| `rtp.max-distance` | int | `10000` | Max blocks from world center |
| `rtp.min-distance` | int | `500` | Min blocks from world center |
| `rtp.op-only` | bool | `false` | Restrict /rtp to ops only |
| `rtp.debug` | bool | `false` | Verbose console logging for RTP flow |
| `discord-webhook.enabled` | bool | `false` | Master toggle for Discord webhooks |
| `discord-webhook.url` | string | `""` | Discord webhook URL |
| `scoreboard.enabled` | bool | `false` | Master toggle for sidebar scoreboard |
| `scoreboard.title` | string | `"&6&lMy Server"` | Scoreboard title (supports & color codes) |
| `scoreboard.lines` | list | *(see code)* | Scoreboard lines — supports placeholders: `{player}` `{ping}` `{kills}` `{deaths}` `{kd}` `{money}` `{online}` `{max}` `{tps}` |
| `scoreboard.update-ticks` | int | `20` | How often to refresh scoreboard (20 = 1 second) |

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
- **OsmiumDiscordWebhook** sends Discord embed notifications via HTTP POST to a configured webhook URL. Uses a daemon single-thread executor for async delivery (sync for server stop). Handles rate limiting with retry. Events: server start/stop, player ban, alt detected/kicked, chat filter triggered, brand enforcement kick.
- **OsmiumRtp** provides a GUI-based `/rtp` command with dimension selection (Overworld/Nether/End), confirmation screen, configurable countdown, and Vault economy integration via reflection. Registers Bukkit permission `minecraft.command.rtp` to override Paper's brigadier permission wrapper. Debug mode logs every step to console under `[Osmium-RTP]`. Location search runs async (virtual thread) to avoid main-thread chunk loading stalls; teleport is scheduled back on the main thread via `server.execute()`.
- **OsmiumScoreboard** sends packet-based sidebar scoreboard to each player. Uses a shared dummy `Scoreboard`+`Objective` for packet construction without touching the player's actual scoreboard. Tracks previous lines per player to send `ClientboundResetScorePacket` for stale entries. Refreshes every `scoreboard.update-ticks` ticks from MinecraftServer.tickChildren().
- **OsmiumUpdateChecker** compares the running server's git commit hash (from `ServerBuildInfo`) against the latest commit on `osmium/main` via the GitHub API. Runs on a virtual thread at startup so it never blocks server init.

## Roadmap

- GrimAC integration (embedded module, not plugin) — auto-download from Modrinth implemented; embedded source removed
- OsmiumRtp: finishing async safe-location search — `findSafeLocation()` now runs on a virtual thread with a "Finding a safe location..." message, disconnect check, and duplicate-pending-teleport guard before scheduling the teleport back on the main thread (in progress, uncommitted as of 2026-08-21)

## Environment

- Java 25 (toolchain enforced in build.gradle.kts)
- Arch Linux dev environment
- SSH auth to GitHub
