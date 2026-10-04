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
    // package-private: the cheat-client simulation suite inspects them
    static final Map<UUID, Set<String>> pendingClients = new ConcurrentHashMap<>();
    // UUIDs that have completed the handshake (sent mod list)
    static final Set<UUID> handshakeCompleted = ConcurrentHashMap.newKeySet();
    // UUID -> tick at which to run the check
    private static final Map<UUID, Integer> scheduledChecks = new ConcurrentHashMap<>();

    // Fingerprint -> UUID for delayed alt check (fingerprint arrives after join)
    private static final Map<UUID, String> playerFingerprints = new ConcurrentHashMap<>();

    // UUIDs that have delivered a hand-shaker payload — accepted OR
    // rejected. Rejected payloads must also count: otherwise a flood of
    // malformed payloads (decode + sha256 + log spam per packet) is never
    // rate-limited by the first-payload guard.
    static final Set<UUID> payloadSeen = ConcurrentHashMap.newKeySet();

    /**
     * Called from ServerCommonPacketListenerImpl.handleCustomPayload() when
     * a "hand-shaker:mods" payload arrives. Decodes the mod list and stores it.
     */
    public static void handleModsPayload(UUID playerUuid, String playerName, byte[] data) {
        if (!OsmiumConfig.brandEnforcementEnabled) return;

        // First payload wins — accepted or not. Later payloads are ignored:
        // a client could otherwise flip its mod list around the scheduled
        // check tick to dodge the blacklist, or flood osmium-fingerprints.json
        // with random fingerprints (unbounded map + disk growth).
        if (!isFirstPayload(playerUuid)) {
            return;
        }
        payloadSeen.add(playerUuid);

        try {
            String modsString = decodeVarIntString(data, 0);
            if (modsString == null) {
                Bukkit.getLogger().warning("[Osmium] Failed to decode mod list from " + playerName);
                return;
            }

            // The Osmium HandShaker always sends 4 length-prefixed fields
            // (mods, sha256, nonce, fingerprint). A missing hash field is a
            // red flag, not a skip-verification path — reject the payload.
            // (Only one payload per player reaches this point — the
            // first-payload guard above — so floods can't spam the log.)
            int offset = varIntStringOffset(data, 0);
            String receivedHash = decodeVarIntString(data, offset);
            if (!verifyHash(modsString, receivedHash)) {
                Bukkit.getLogger().warning("[Osmium] Mod list payload missing/mismatched hash from " + playerName + ", rejecting");
                return;
            }

            // Skip nonce (3rd field) to get fingerprint (4th field)
            int nonceOffset = varIntStringOffset(data, offset);
            int fingerprintOffset = varIntStringOffset(data, nonceOffset);
            String fingerprint = null;
            if (fingerprintOffset < data.length) {
                fingerprint = decodeVarIntString(data, fingerprintOffset);
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

            // Record fingerprint for alt detection if present. Only
            // well-formed fingerprints (64-char hex, as the HandShaker mod
            // sends) are stored — anything else is client-controlled junk
            // and would pollute the fingerprint store.
            if (isValidFingerprint(fingerprint)) {
                OsmiumAltTracker.recordFingerprint(fingerprint, playerUuid);
                playerFingerprints.put(playerUuid, fingerprint);
                Bukkit.getLogger().info("[Osmium] Received mod list from " + playerName + ": " + mods + " (fp: " + fingerprint.substring(0, 8) + "...)");

                // Check if this device has a banned alt (fingerprint-based alt detection)
                if (OsmiumConfig.altBanEnabled) {
                    Set<UUID> fpLinked = OsmiumAltTracker.getUuidsForFingerprint(fingerprint);
                    for (UUID linkedUuid : fpLinked) {
                        if (linkedUuid.equals(playerUuid)) continue;
                        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
                        if (server != null && server.getPlayerList().getBans().isBanned(
                                new net.minecraft.server.players.NameAndId(linkedUuid, ""))) {
                            // Kick the alt
                            net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
                            if (player != null && player.connection != null && player.connection.isAcceptingMessages()) {
                                String bannedName = null;
                                org.bukkit.OfflinePlayer offlineBanned = Bukkit.getOfflinePlayer(linkedUuid);
                                if (offlineBanned.getName() != null) bannedName = offlineBanned.getName();
                                OsmiumDiscordWebhook.sendAltKicked(playerName, playerUuid, bannedName);
                                player.connection.disconnect(net.minecraft.network.chat.Component.literal(OsmiumConfig.altBanKickMessage));
                            }
                            return;
                        }
                    }
                }
            } else {
                Bukkit.getLogger().info("[Osmium] Received mod list from " + playerName + ": " + mods + " (no fingerprint)");
            }
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

                String brand = player.connection.clientBrand;
                boolean hs = handshakeCompleted.contains(uuid);
                Bukkit.getLogger().info("[Osmium] Brand check for " + player.getPlainTextName()
                        + ": brand=" + brand + ", handshake=" + hs
                        + ", mods=" + pendingClients.getOrDefault(uuid, java.util.Collections.emptySet()));
                String kickMsg = checkPlayer(uuid, brand);
                if (kickMsg != null) {
                    OsmiumDiscordWebhook.sendBrandKick(player.getPlainTextName(), uuid, kickMsg); // Osmium - discord webhook
                    player.connection.disconnect(net.minecraft.network.chat.Component.literal(kickMsg));
                }
            }
        }
    }

    /**
     * Checks a player against brand enforcement rules.
     * Returns a kick message if the player should be kicked, or null if they pass.
     *
     * Modes:
     * - strict: ALL clients must have HandShaker
     * - vanilla: vanilla clients pass, modded clients without HandShaker get kicked
     */
    public static String checkPlayer(UUID playerUuid, String brand) {
        if (!OsmiumConfig.brandEnforcementEnabled) return null;

        boolean hasHandshake = handshakeCompleted.contains(playerUuid);
        Set<String> mods = pendingClients.getOrDefault(playerUuid, Collections.emptySet());
        boolean isModdedClient = brand != null && !brand.isEmpty()
                && !"vanilla".equalsIgnoreCase(brand)
                && !"Osmium".equalsIgnoreCase(brand); // our own brand shouldn't count

        // In strict mode, all clients must have the HandShaker mod
        if ("strict".equalsIgnoreCase(OsmiumConfig.brandEnforcementMode) && !hasHandshake) {
            return OsmiumConfig.brandEnforcementKickMessage;
        }

        // In vanilla mode, modded clients without HandShaker get kicked
        // Vanilla clients are completely ignored
        if ("vanilla".equalsIgnoreCase(OsmiumConfig.brandEnforcementMode) && isModdedClient && !hasHandshake) {
            return OsmiumConfig.brandEnforcementKickMessage;
        }

        // Require Osmium's HandShaker fork (sends fingerprint) — reject stock HandShaker
        if (OsmiumConfig.brandEnforcementRequireOsmiumHandshaker && hasHandshake
                && !playerFingerprints.containsKey(playerUuid)) {
            return OsmiumConfig.brandEnforcementKickMessage;
        }

        // Check required mods — only for clients that sent a HandShaker payload
        List<String> requiredMods = OsmiumConfig.brandEnforcementRequiredMods;
        if (!requiredMods.isEmpty() && hasHandshake) {
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
                return OsmiumConfig.brandEnforcementBlacklistKickMessage
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
        playerFingerprints.remove(playerUuid);
        payloadSeen.remove(playerUuid);
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

    /** True until the client has delivered its one hand-shaker payload
     *  (accepted or rejected — see payloadSeen). */
    static boolean isFirstPayload(UUID playerUuid) {
        return !pendingClients.containsKey(playerUuid) && !handshakeCompleted.contains(playerUuid)
                && !payloadSeen.contains(playerUuid);
    }

    /** sha256(modsString) must equal the client-claimed hash; a missing
     *  hash never passes. */
    static boolean verifyHash(String modsString, String receivedHash) {
        return receivedHash != null && sha256(modsString).equals(receivedHash);
    }

    /** Test hook for the cheat-client simulation suite. */
    static String sha256ForTest(String input) {
        return sha256(input);
    }

    /** The HandShaker mod sends a sha256 digest: exactly 64 hex chars. */
    static boolean isValidFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() != 64) return false;
        for (int i = 0; i < fingerprint.length(); i++) {
            char c = fingerprint.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
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
