package org.osmium.anticheat;

import org.bukkit.Bukkit;
import org.osmium.OsmiumConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Sends Discord webhook notifications for server events:
 * server start/stop, player bans, and alt account detections.
 */
public class OsmiumDiscordWebhook {

    // Bounded queue: Discord rate-limits to ~30 req/min. Under a mass-ban or
    // join wave, drop the OLDEST queued embed instead of growing memory and
    // delivering minutes-stale messages.
    private static final ExecutorService EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "Osmium-Discord-Webhook");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    // Embed colors
    private static final int COLOR_GREEN  = 0x2ECC71; // server start
    private static final int COLOR_RED    = 0xE74C3C; // server stop, ban
    private static final int COLOR_ORANGE = 0xE67E22; // alt detection
    private static final int COLOR_YELLOW = 0xF1C40F; // chat filter

    // -------------------------------------------------------------------------
    // Public API — call these from NMS / Osmium hooks
    // -------------------------------------------------------------------------

    public static void sendServerStart() {
        if (!isEnabled()) return;
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        sendEmbed("Server Started",
                "The server is now online and accepting connections.\n\n" +
                "**Players:** " + online + "/" + max,
                COLOR_GREEN);
    }

    public static void sendServerStop() {
        if (!isEnabled()) return;
        // Build ping string from configured user IDs
        StringBuilder pings = new StringBuilder();
        for (String userId : OsmiumConfig.discordWebhookPingOnStop) {
            if (userId != null && !userId.isBlank()) {
                pings.append("<@").append(userId.trim()).append("> ");
            }
        }
        sendEmbedSync("Server Stopped",
                "The server has shut down.",
                COLOR_RED,
                pings.toString().trim());
    }

    public static void sendBan(String playerName, UUID playerUuid, String reason, String source) {
        if (!isEnabled()) return;
        String desc = "**Player:** " + playerName + "\n" +
                "**UUID:** `" + playerUuid + "`\n" +
                "**Reason:** " + (reason != null ? reason : "No reason given") + "\n" +
                "**Banned by:** " + (source != null ? source : "Unknown");
        sendEmbed("Player Banned", desc, COLOR_RED);
    }

    public static void sendAltDetected(String playerName, UUID playerUuid, String ip, Set<UUID> linkedUuids) {
        if (!isEnabled()) return;
        StringBuilder linked = new StringBuilder();
        for (UUID u : linkedUuids) {
            if (!u.equals(playerUuid)) {
                String name = Bukkit.getOfflinePlayer(u).getName();
                linked.append("• `").append(name != null ? name : u.toString()).append("`\n");
            }
        }
        if (linked.isEmpty()) linked.append("*(none)*\n");

        String desc = "**Player:** " + playerName + "\n" +
                "**UUID:** `" + playerUuid + "`\n" +
                "**Linked accounts:**\n" + linked;
        sendEmbed("Alt Account Detected", desc, COLOR_ORANGE);
    }

    public static void sendAltKicked(String playerName, UUID playerUuid, String bannedPlayerName) {
        if (!isEnabled()) return;
        String desc = "**Player:** " + playerName + "\n" +
                "**UUID:** `" + playerUuid + "`\n" +
                "**Shares IP with banned player:** " + (bannedPlayerName != null ? bannedPlayerName : "Unknown");
        sendEmbed("Alt Account Kicked", desc, COLOR_ORANGE);
    }

    public static void sendChatFiltered(String playerName, String message, String matchedPattern, String action) {
        if (!isEnabled()) return;
        String desc = "**Player:** " + playerName + "\n" +
                "**Message:** ||" + escapeJson(message) + "||\n" +
                "**Matched:** `" + matchedPattern + "`\n" +
                "**Action:** " + action;
        sendEmbed("Chat Filter Triggered", desc, COLOR_YELLOW);
    }

    public static void sendBrandKick(String playerName, UUID playerUuid, String reason) {
        if (!isEnabled()) return;
        String desc = "**Player:** " + playerName + "\n" +
                "**UUID:** `" + playerUuid + "`\n" +
                "**Reason:** " + reason;
        sendEmbed("Brand Enforcement Kick", desc, COLOR_RED);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static boolean isEnabled() {
        return OsmiumConfig.discordWebhookEnabled
                && OsmiumConfig.discordWebhookUrl != null
                && !OsmiumConfig.discordWebhookUrl.isBlank();
    }

    private static void sendEmbed(String title, String description, int color) {
        EXECUTOR.submit(() -> doSend(title, description, color, null));
    }

    /** Semi-async send — used for server stop. Sends on the executor thread with a short wait. */
    private static void sendEmbedSync(String title, String description, int color, String content) {
        try {
            EXECUTOR.submit(() -> doSend(title, description, color, content)).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Timed out or failed — don't block shutdown
        }
    }

    private static void doSend(String title, String description, int color, String content) {
        String url = OsmiumConfig.discordWebhookUrl;
        if (url == null || url.isBlank()) return;

        String timestamp = Instant.now().toString();
        StringBuilder json = new StringBuilder();
        json.append("{");
        if (content != null && !content.isBlank()) {
            json.append("\"content\":\"").append(escapeJson(content)).append("\",");
        }
        json.append("\"embeds\":[{")
            .append("\"title\":\"").append(escapeJson(title)).append("\",")
            .append("\"description\":\"").append(escapeJson(description)).append("\",")
            .append("\"color\":").append(color).append(",")
            .append("\"footer\":{\"text\":\"Osmium\"},")
            .append("\"timestamp\":\"").append(timestamp).append("\"")
            .append("}]}");

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code == 429) {
                // Rate limited — wait and retry once
                String retryAfter = conn.getHeaderField("Retry-After");
                long waitMs = retryAfter != null ? (long) (Double.parseDouble(retryAfter) * 1000) : 1000;
                Thread.sleep(Math.min(waitMs, 10000));
                conn.disconnect();
                doSend(title, description, color, content);
                return;
            }
            if (code < 200 || code >= 300) {
                Bukkit.getLogger().warning("[Osmium] Discord webhook returned HTTP " + code);
            }
            conn.disconnect();
        } catch (IOException e) {
            Bukkit.getLogger().log(Level.WARNING, "[Osmium] Failed to send Discord webhook", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}