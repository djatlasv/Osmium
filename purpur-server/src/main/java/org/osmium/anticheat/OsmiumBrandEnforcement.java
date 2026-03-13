package org.osmium.anticheat;

import org.bukkit.Bukkit;
import org.osmium.OsmiumConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Native brand enforcement — replaces the HandShaker Paper plugin.
 * Listens for mod list payloads on the "hand-shaker:mods" plugin channel,
 * then enforces required/blacklisted mod rules after a configurable delay.
 *
 * Supports two modes:
 * - STRICT: all clients must have the HandShaker mod installed
 * - VANILLA: vanilla clients (no HandShaker) are allowed but still checked against required mods
 */
public class OsmiumBrandEnforcement {

    public static final String MODS_CHANNEL = "hand-shaker:mods";

    // UUID -> set of mod IDs received from the client
    private static final Map<UUID, Set<String>> pendingClients = new ConcurrentHashMap<>();
    // UUIDs that have completed the handshake (sent mod list)
    private static final Set<UUID> handshakeCompleted = ConcurrentHashMap.newKeySet();
    // UUID -> tick at which to run the check
    private static final Map<UUID, Integer> scheduledChecks = new ConcurrentHashMap<>();

    /**
     * Called from ServerCommonPacketListenerImpl.handleCustomPayload() when
     * a "hand-shaker:mods" payload arrives. Decodes the mod list and stores it.
     */
    public static void handleModsPayload(UUID playerUuid, String playerName, byte[] data) {
        if (!OsmiumConfig.brandEnforcementEnabled) return;

        try {
            String modsString = decodeVarIntString(data, 0);
            if (modsString == null) {
                Bukkit.getLogger().warning("[Osmium] Failed to decode mod list from " + playerName);
                return;
            }

            // Decode and verify hash
            int offset = varIntStringOffset(data, 0);
            String receivedHash = decodeVarIntString(data, offset);
            if (receivedHash != null) {
                String calculatedHash = sha256(modsString);
                if (!calculatedHash.equals(receivedHash)) {
                    Bukkit.getLogger().warning("[Osmium] Mod list hash mismatch from " + playerName + ", rejecting");
                    return;
                }
            }

            // Parse comma-separated mod IDs
            Set<String> mods = new HashSet<>();
            if (!modsString.isBlank()) {
                for (String s : modsString.split(",")) {
                    if (!s.isBlank()) mods.add(s.trim().toLowerCase(Locale.ROOT));
                }
            }

            pendingClients.put(playerUuid, mods);
            handshakeCompleted.add(playerUuid);

            Bukkit.getLogger().info("[Osmium] Received mod list from " + playerName + ": " + mods);
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "[Osmium] Error decoding mod payload from " + playerName, e);
        }
    }

    /**
     * Schedules a brand enforcement check for the given player at the specified tick.
     * Called from markClientLoaded() in ServerGamePacketListenerImpl.
     */
    public static void scheduleCheck(UUID playerUuid, int checkAtTick) {
        scheduledChecks.put(playerUuid, checkAtTick);
    }

    /**
     * Called every tick from MinecraftServer.tickChildren().
     * Processes any scheduled checks whose tick has arrived.
     */
    public static void tick(int currentTick, net.minecraft.server.MinecraftServer server) {
        if (scheduledChecks.isEmpty()) return;

        Iterator<Map.Entry<UUID, Integer>> it = scheduledChecks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            if (currentTick >= entry.getValue()) {
                it.remove();
                UUID uuid = entry.getKey();

                // Find the player — they may have disconnected
                net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player == null || player.connection == null || !player.connection.isAcceptingMessages()) {
                    removePlayer(uuid);
                    continue;
                }

                String kickMsg = checkPlayer(uuid);
                if (kickMsg != null) {
                    player.connection.disconnect(net.minecraft.network.chat.Component.literal(kickMsg));
                }
            }
        }
    }

    /**
     * Checks a player against brand enforcement rules.
     * Returns a kick message if the player should be kicked, or null if they pass.
     */
    public static String checkPlayer(UUID playerUuid) {
        if (!OsmiumConfig.brandEnforcementEnabled) return null;

        boolean hasHandshake = handshakeCompleted.contains(playerUuid);
        Set<String> mods = pendingClients.getOrDefault(playerUuid, Collections.emptySet());

        // In strict mode, all clients must have the HandShaker mod
        if ("strict".equalsIgnoreCase(OsmiumConfig.brandEnforcementMode) && !hasHandshake) {
            return OsmiumConfig.brandEnforcementKickMessage;
        }

        // Check required mods — applies in BOTH strict and vanilla modes
        List<String> requiredMods = OsmiumConfig.brandEnforcementRequiredMods;
        if (!requiredMods.isEmpty()) {
            // In vanilla mode, skip required-mod check if client has no handshake
            // (they can't report mods they don't know about)
            // BUT if hand-shaker itself is required, vanilla clients get kicked
            if (!hasHandshake) {
                for (String required : requiredMods) {
                    if ("hand-shaker".equalsIgnoreCase(required)) {
                        return OsmiumConfig.brandEnforcementKickMessage;
                    }
                }
            } else {
                Set<String> missing = new LinkedHashSet<>();
                for (String required : requiredMods) {
                    if (!mods.contains(required.toLowerCase(Locale.ROOT))) {
                        missing.add(required);
                    }
                }
                if (!missing.isEmpty()) {
                    return OsmiumConfig.brandEnforcementKickMessage
                            .replace("{mods}", String.join(", ", missing));
                }
            }
        }

        // Check blacklisted mods — only meaningful if client sent a mod list
        if (hasHandshake) {
            List<String> blacklisted = OsmiumConfig.brandEnforcementBlacklistedMods;
            Set<String> found = new LinkedHashSet<>();
            for (String banned : blacklisted) {
                if (mods.contains(banned.toLowerCase(Locale.ROOT))) {
                    found.add(banned);
                }
            }
            if (!found.isEmpty()) {
                return OsmiumConfig.brandEnforcementKickMessage
                        .replace("{mods}", String.join(", ", found));
            }
        }

        return null;
    }

    /**
     * Cleans up state for a player (call on disconnect).
     */
    public static void removePlayer(UUID playerUuid) {
        pendingClients.remove(playerUuid);
        handshakeCompleted.remove(playerUuid);
        scheduledChecks.remove(playerUuid);
    }

    // -- VarInt string decoding (Minecraft protocol format) --

    private static String decodeVarIntString(byte[] data, int startOffset) {
        try {
            int idx = startOffset;
            int numRead = 0;
            int result = 0;
            byte read;
            do {
                if (idx >= data.length) return null;
                read = data[idx++];
                int value = (read & 0x7F);
                result |= (value << (7 * numRead));
                numRead++;
                if (numRead > 5) return null;
            } while ((read & 0x80) != 0);

            int length = result;
            if (length < 0 || idx + length > data.length) return null;
            return new String(data, idx, length, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static int varIntStringOffset(byte[] data, int startOffset) {
        try {
            int idx = startOffset;
            int numRead = 0;
            int result = 0;
            byte read;
            do {
                if (idx >= data.length) return data.length;
                read = data[idx++];
                int value = (read & 0x7F);
                result |= (value << (7 * numRead));
                numRead++;
                if (numRead > 5) return data.length;
            } while ((read & 0x80) != 0);

            int length = result;
            return idx + length;
        } catch (Exception e) {
            return data.length;
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
