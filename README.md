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

**Chunk hiding** replaces Orebfuscator and similar plugins. Deepslate variants and base indicator blocks (chests, furnaces, crafting tables, etc.) are replaced with stone or deepslate in the chunk packet before it leaves the server. Players within a configurable proximity radius receive the real chunk data. This runs as a second pass on top of Paper's existing anti-xray engine.

**Y-level hiding** hides all blocks below a configurable Y threshold, replacing Phantom. When enabled, every block below the threshold is replaced in the packet regardless of type.

**Alt detection and ban** tracks IP-to-UUID associations and automatically bans alt accounts that share an IP with a banned player. Mappings are persisted to `osmium-ips.json` using a thread-safe ConcurrentHashMap.

**Native chat filter** replaces chat filter plugins with a built-in word list. Supports exact (case-insensitive) and regex patterns via `osmium-words.json`. Configurable actions: block, kick, or mute.

**Brand enforcement** integrates [HandShaker](https://github.com/djatlasv/hand-shaker) protocol natively at the NMS level. Intercepts `hand-shaker:mods` plugin channel payloads, verifies SHA-256 hashes, and enforces required/blacklisted mod lists. Supports strict mode (all clients must have HandShaker) and vanilla mode (vanilla clients allowed, mod rules still enforced). Includes the vanilla-mode fix for the upstream HandShaker plugin bug where vanilla clients bypassed all mod checks.

**GrimAC integration** (planned) embeds GrimAC as a module rather than a plugin for direct packet access.

## Configuration

Osmium generates `osmium.yml` in the server root on first start.
```yaml
chunk-hiding:
  enabled: true
  y-threshold: 0
  proximity-radius: 32

y-level-hiding:
  enabled: false
  threshold: -32

brand-enforcement:
  enabled: false
  mode: vanilla              # strict or vanilla
  kick-message: "You must use the HandShaker mod. Get it at: discord.gg/yourserver"
  check-delay-ticks: 100     # 5 seconds — time to wait for HandShaker payload
  required-mods: []          # e.g. [hand-shaker]
  blacklisted-mods: []       # e.g. [wurst, meteor-client]

alt-ban:
  enabled: false
  kick-message: "You are banned (alt account detected)."

chat-filter:
  enabled: false
  action: block          # block, kick, or mute
  message: "Your message was blocked by the chat filter."
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
./gradlew createMojmapBundlerJar
```

The compiled jar will be in `purpur-server/build/libs`.

* Note: this is a personal project meant for **small servers** only. This is also for me to learn minecraft specific java.

## Credits

Osmium is built on top of [Purpur](https://github.com/PurpurMC/Purpur) by PurpurMC, which is built on [Paper](https://github.com/PaperMC/Paper). The Osmium-specific patches were written with the assistance of Claude (Anthropic).

Upstream credits: [PaperMC/Paper](https://github.com/PaperMC/Paper), [PaperMC/paperweight](https://github.com/PaperMC/paperweight), [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur).

## License

All Osmium patches are licensed under the MIT license. See [LICENSE](LICENSE) for the full text including upstream copyright notices.
