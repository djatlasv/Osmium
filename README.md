<div align="center">

# Osmium

[![MIT License](https://img.shields.io/github/license/djatlasv/Osmium?&logo=github)](LICENSE)
[![Build Status](https://img.shields.io/github/actions/workflow/status/djatlasv/Osmium/build.yml?branch=osmium%2Fmain&event=push)](https://github.com/djatlasv/Osmium)

Osmium is a custom [Purpur](https://github.com/PurpurMC/Purpur) fork for semi-vanilla SMPs. It moves anticheat and security features out of the plugin layer and into the server itself, eliminating Bukkit event overhead and keeping everything in the same JVM context.

</div>

## What makes Osmium different

Most servers handle anticheat and obfuscation through plugins. Plugins sit on top of the Bukkit event system, which means every packet, block change, and player action goes through an event pipeline before any plugin can act on it. Osmium cuts that out entirely by building these features directly into the server at the NMS level.

The result is less overhead, no plugin conflicts, and obfuscation that runs in the same thread as chunk serialization rather than in a separate listener.

## Features

**Chunk hiding** replaces all blocks below a configurable Y level with a fake block (default: deepslate) in the chunk packet before it leaves the server. Players within a configurable proximity radius see the real blocks, and the fake blocks reappear when they move away. Runs as a second pass on top of Paper's existing anti-xray engine. The replacement block is configurable — use any vanilla block name. Optionally hides entities (mobs, items, minecarts, etc.) below the threshold from distant players too.

**Alt detection and ban** tracks player associations using both IP addresses and hardware fingerprints for alt account detection. IP-to-UUID mappings are persisted to `osmium-ips.json`, and device fingerprints (sent via HandShaker) are stored in `osmium-fingerprints.json`. Fingerprint-based tracking identifies individual devices even on shared networks, avoiding false positives for players on the same WiFi. **Requires `brand-enforcement.enabled: true`** — the fingerprint is received through the HandShaker protocol, so brand enforcement must be on for fingerprint-based alt detection to work. IP-based alt detection works regardless.

**Native chat filter** replaces chat filter plugins with a built-in word list. Supports exact (case-insensitive) and regex patterns via `osmium-words.json`. Configurable actions: block, kick, or mute.

**Brand enforcement** integrates the [HandShaker](https://github.com/djatlasv/Hand-shaker) protocol natively at the NMS level. Intercepts `hand-shaker:mods` plugin channel payloads, verifies SHA-256 hashes, and enforces required/blacklisted mod lists. Supports strict mode (all clients must have HandShaker) and vanilla mode (vanilla clients allowed, mod rules still enforced).

> **Note:** You need the [custom Osmium fork of HandShaker](https://github.com/djatlasv/Hand-shaker) for brand enforcement and fingerprint-based alt detection to work. Pre-built JARs for Fabric and NeoForge are available in that repo.

**GrimAC auto-install** — when `grim.enabled` is set to `true`, Osmium automatically downloads the latest [GrimAC](https://github.com/GrimAnticheat/Grim) plugin from Modrinth into `./plugins/` on first startup. A server restart is required after the initial download. GrimAC configs live in `./plugins/GrimAC/`.

**Discord webhooks** sends embed notifications to a Discord channel for server start/stop, player bans, alt detections, chat filter triggers, and brand enforcement kicks.

## Configuration

Osmium generates `osmium.yml` in the server root on first start. Delete it to regenerate with defaults.

```yaml
chunk-hiding:
  enabled: false
  y-threshold: 0           # hide all blocks below this Y level
  block: deepslate          # replacement block (any vanilla block name)
  proximity-radius: 32      # blocks around the player where real blocks are revealed
  hide-entities: true        # also hide entities (mobs, items, etc.) below the threshold

brand-enforcement:
  enabled: false
  mode: vanilla              # strict or vanilla
  kick-message: "You must use the HandShaker mod. Get it at: discord.gg/yourserver"
  check-delay-ticks: 100     # ticks to wait for HandShaker payload (100 = 5 seconds)
  required-mods: []          # e.g. [hand-shaker]
  blacklisted-mods: []       # e.g. [wurst, meteor-client]

alt-ban:
  enabled: false             # requires brand-enforcement.enabled for fingerprint-based detection
  kick-message: "You are banned (alt account detected)."

chat-filter:
  enabled: false
  action: block              # block, kick, or mute
  message: "Your message was blocked by the chat filter."

grim:
  enabled: false             # auto-download GrimAC plugin from Modrinth on first start

discord-webhook:
  enabled: false
  url: ""                    # your Discord webhook URL
```

## Building

Clone the repository, then run:
```
./gradlew applyAllPatches
```

To build:
```
./gradlew build -x test
```

To produce a server-ready jar:
```
./gradlew purpur-server:createBundlerJar
```

The runnable jars land in `purpur-server/build/libs` (paperclip jar is the one to launch).

> **Note:** this is a personal project meant for **small servers** only. This is also for me to learn minecraft specific java.

## Credits

Osmium is built on top of [Purpur](https://github.com/PurpurMC/Purpur) by PurpurMC, which is built on [Paper](https://github.com/PaperMC/Paper). The Osmium-specific patches were written with the assistance of Claude (Anthropic).

Upstream credits: [PaperMC/Paper](https://github.com/PaperMC/Paper), [PaperMC/paperweight](https://github.com/PaperMC/paperweight), [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur), [GrimAnticheat/Grim](https://github.com/GrimAnticheat/Grim).

## License

All Osmium patches are licensed under the MIT license. See [LICENSE](LICENSE) for the full text including upstream copyright notices.
