<div align="center">

# Osmium server software

[![MIT License](https://img.shields.io/github/license/djatlasv/Osmium?&logo=github)](LICENSE)
[![Build Status](https://img.shields.io/github/actions/workflow/status/djatlasv/Osmium/build.yml?branch=osmium%2Fmain&event=push)](https://github.com/djatlasv/Osmium)
![Version](https://img.shields.io/badge/version-2.7.0-blue)
![Minecraft](https://img.shields.io/badge/Minecraft-26.3-green)
![Java](https://img.shields.io/badge/Java-25-orange)

Osmium is a custom [Purpur](https://github.com/PurpurMC/Purpur) fork for semi-vanilla SMPs. It moves anticheat, security, and quality-of-life features out of the plugin layer and into the server itself — native NMS-level code instead of Bukkit event pipelines.

</div>

## Requirements

| | |
|---|---|
| **Osmium version** | 2.7.0 (latest release) |
| **Minecraft / Purpur base** | 26.3 |
| **Java** | 25 or newer |
| **Client mod (optional)** | [HandShaker](https://github.com/djatlasv/Hand-shaker) — Fabric & NeoForge |

## Why Osmium

Most servers handle anticheat and obfuscation through plugins. Plugins sit on top of the Bukkit event system, which means every packet, block change, and player action goes through an event pipeline before any plugin can act on it. Osmium builds these features directly into the server at the NMS level:

- less overhead — no event pipeline for security checks
- no plugin conflicts
- obfuscation that runs in the same thread as chunk serialization

## Features

### Anticheat & security

- **Chunk hiding** — replaces all blocks below a configurable Y level with a fake block (default: deepslate) in the chunk packet before it leaves the server. Players within a proximity radius see the real blocks; fake blocks reappear when they move away. The replacement block is configurable, and entities below the threshold can be hidden too.
- **Raytrace hiding** — a full RayTraceAntiXray-model occlusion engine built in. Per-player candidate tracking, crack-detection ray tracing (DDA), and frustum culling reveal only blocks a player can legitimately see. Buried ores stay hidden until actually dug to.
- **Entity occlusion** — mobs, items, and other entities hidden behind terrain are not sent until visible.
- **Alt detection & ban** — tracks player associations by IP *and* hardware fingerprint. Fingerprint tracking identifies individual devices even on shared networks. IP mappings persist to `osmium-ips.json`, fingerprints to `osmium-fingerprints.json`.
- **Brand enforcement** — integrates the [HandShaker](https://github.com/djatlasv/Hand-shaker) protocol natively. Intercepts `hand-shaker:mods` payloads, verifies SHA-256 hashes, and enforces required/blacklisted mod lists. Strict mode (HandShaker required) or vanilla mode (vanilla clients allowed, mod rules still enforced).
- **Latency warning** — warns players with sustained high ping that high latency can cause false anticheat bans.
- **GrimAC auto-install** — when enabled, automatically downloads [GrimAC](https://github.com/GrimAnticheat/Grim) from Modrinth into `./plugins/` on first startup, and extracts bundled tuned configs.

> **Note:** brand enforcement and fingerprint-based alt detection require the [HandShaker](https://github.com/djatlasv/Hand-shaker) client mod. Pre-built JARs for Fabric and NeoForge are available in that repo.

### Utility & moderation

- **Native chat filter** — built-in word list with exact and regex patterns (`osmium-words.json`); block, kick, or mute actions.
- **Discord bot** — slash commands with permission tiers, chat bridge, console output capture, `/tempban`, and reports.
- **Staff log & linking** — in-game staff commands are captured and pushed to a staff log channel. Staff link their Minecraft account via `/link` (one-time codes) — required for helper+ Discord actions.
- **Discord webhooks** — embed notifications for server start/stop, bans, alt detections, chat filter triggers, and brand enforcement kicks.
- **`/setop`** — in-game op level management (0–4) with hierarchy enforcement.
- **`/report`** — player reports pushed to the configured Discord channel.
- **Backups** — interval zips of worlds + configs with keep-N pruning.

### Gameplay & QoL

- **`/rtp`** — random teleport with a GUI, uniform-area search, and cooldowns.
- **`/tpa`** — teleport requests with clickable accept/deny and move-cancel countdowns.
- **`/home` / `/sethome` / `/homes` / `/spawn`** — homes system with optional dedicated spawn dimension.
- **`/team`** — team system with GUIs, chat invites, friendly-fire toggle, and promotion/kick/rename.
- **Combat tag** — PvP combat tagging with action-bar countdown; tagged players can't teleport or use RTP.
- **Chat tags** — role prefixes (`[Owner]`, `[Admin]`, `[Mod]`) and team names in chat.
- **Packet-based scoreboard** with live placeholders (`{player}`, `{tps}`, `{combat}`, …).

## Running a server

Download the latest paperclip jar from [Releases](https://github.com/djatlasv/Osmium/releases) (e.g. `purpur-paperclip-2.7.0.jar`) and launch it:

```bash
java -Xms4G -Xmx4G -jar purpur-paperclip-2.7.0.jar --nogui
```

Aikar's flags are recommended:

```bash
java -Xms10G -Xmx10G -XX:+UseG1GC -XX:+ParallelRefProcEnabled \
  -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions \
  -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 \
  -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 \
  -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \
  -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \
  -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 \
  -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 \
  -jar purpur-paperclip-2.7.0.jar --nogui
```

On first run the paperclip prepares the server and Osmium generates `osmium.yml` in the server root. Delete it to regenerate with defaults.

### Example configuration

```yaml
chunk-hiding:
  enabled: false
  y-threshold: 0           # hide all blocks below this Y level
  block: deepslate         # replacement block (any vanilla block name)
  proximity-radius: 32     # blocks around the player where real blocks are revealed
  hide-entities: true      # also hide entities below the threshold

brand-enforcement:
  enabled: false
  mode: vanilla            # strict or vanilla
  kick-message: "You must use the HandShaker mod. Get it at: discord.gg/yourserver"
  check-delay-ticks: 100   # ticks to wait for the HandShaker payload
  required-mods: []        # e.g. [hand-shaker]
  blacklisted-mods: []     # e.g. [wurst, meteor-client]

alt-ban:
  enabled: false           # requires brand-enforcement.enabled for fingerprint detection
  kick-message: "You are banned (alt account detected)."

chat-filter:
  enabled: false
  action: block            # block, kick, or mute
  message: "Your message was blocked by the chat filter."

grim:
  enabled: false           # auto-download GrimAC from Modrinth on first start

discord-webhook:
  enabled: false
  url: ""                  # your Discord webhook URL
```

Every feature is config-gated and defaults to OFF unless noted, so Osmium behaves like stock Purpur until you turn things on.

## Building from source

```bash
./gradlew applyAllPatches                  # FIRST, after clone or any patch change
./gradlew build -x test                    # compile everything
./gradlew :purpur-server:test              # run unit tests (~9k)
./gradlew purpur-server:createPaperclipJar # runnable paperclip jar
./gradlew purpur-server:createBundlerJar   # bundler jar
```

The jars land in `purpur-server/build/libs`. Launch the **paperclip** jar.

- **Upstream:** Paper → Purpur → Osmium (MC 26.3, Java 25, Gradle wrapper 9.7.1)
- `applyAllPatches` must fully finish **before** running `build` — run them as separate commands.

> **Note:** this is a personal project meant for **small servers** only. It's also how I learn Minecraft-specific Java.

## Credits

Osmium is built on top of [Purpur](https://github.com/PurpurMC/Purpur) by PurpurMC, which is built on [Paper](https://github.com/PaperMC/Paper). The raytrace occlusion engine follows the model of [RayTraceAntiXray](https://github.com/stonar96/RayTraceAntiXray) by stonar96 (MIT). The Osmium-specific patches were written with the assistance of Claude (Anthropic).

Upstream credits: [PaperMC/Paper](https://github.com/PaperMC/Paper), [PaperMC/paperweight](https://github.com/PaperMC/paperweight), [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur), [GrimAnticheat/Grim](https://github.com/GrimAnticheat/Grim), [stonar96/RayTraceAntiXray](https://github.com/stonar96/RayTraceAntiXray), [djatlasv/Hand-shaker](https://github.com/djatlasv/Hand-shaker).

## License

All Osmium patches are licensed under the MIT license. See [LICENSE](LICENSE) for the full text including upstream copyright notices.
