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

**Brand enforcement** (in progress) detects Fabric clients that have not completed the HandShaker handshake and kicks them with a configurable message and Discord link.

**Alt ban and IP tracking** (planned) checks joining players against the IP addresses of banned players and auto-bans matches.

**Native chat filter** (planned) replaces ChatFilter plugin with a built-in word list and configurable actions.

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
  kick-message: "You must use the HandShaker mod. Get it at: discord.gg/yourserver"
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
