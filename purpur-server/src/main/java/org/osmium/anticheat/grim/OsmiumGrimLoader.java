package org.osmium.anticheat.grim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Auto-installs GrimAC as a plugin JAR into ./plugins/ on first startup.
 * Uses the Modrinth API to find and download the latest version.
 * Paper's plugin loader handles the rest.
 */
public class OsmiumGrimLoader {

    private static final Logger LOGGER = Logger.getLogger("Osmium-GrimAC");
    private static final String MODRINTH_PROJECT = "LJNGWSvH"; // GrimAC project ID

    /**
     * Called during server init if grim.enabled is true.
     * Ensures GrimAC plugin JAR exists in ./plugins/, downloading if needed.
     */
    public static void init() {
        File pluginsDir = new File("plugins");
        pluginsDir.mkdirs();

        // Check for any existing GrimAC jar
        File[] existing = pluginsDir.listFiles((dir, name) ->
                name.toLowerCase().startsWith("grim") && name.endsWith(".jar"));
        if (existing != null && existing.length > 0) {
            LOGGER.info("GrimAC plugin found: " + existing[0].getName());
            return;
        }

        LOGGER.info("GrimAC not found in plugins/. Fetching latest version from Modrinth...");
        try {
            downloadLatestFromModrinth(pluginsDir);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to download GrimAC from Modrinth. Install it manually.", e);
        }
    }

    /** Sync webhook URL on every startup so GrimAC always uses Osmium's URL. */
    public static void start() {
        // Safety net for installs where the jar exists but configs were never
        // extracted (or GrimAC was disabled before writing its own defaults).
        // Only-if-absent, so it never clobbers a live GrimAC config.
        extractDefaultConfigs();
        syncWebhookUrl();
    }

    /** No-op — GrimAC stops itself as a plugin via Paper's loader. */
    public static void stop() {}

    private static void downloadLatestFromModrinth(File pluginsDir) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        // Prefer a build whose Modrinth metadata declares THIS Minecraft version,
        // so an MC upgrade can't silently pull a Grim that can't run on it.
        // Fall back to the unfiltered latest if metadata lags upstream
        // (GrimAC 2.3.74 shades packetevents with 26.3 support but its
        // Modrinth game_versions metadata only lists up to 26.2).
        String mcVersion = net.minecraft.SharedConstants.getCurrentVersion().id();
        String encodedVersions = java.net.URLEncoder.encode("[\"" + mcVersion + "\"]", java.nio.charset.StandardCharsets.UTF_8);
        String baseVersionsUrl = "https://api.modrinth.com/v2/project/" + MODRINTH_PROJECT + "/version?loaders=%5B%22paper%22%5D";

        HttpResponse<String> versionResp = queryModrinth(client, baseVersionsUrl + "&game_versions=" + encodedVersions);
        if (versionResp.statusCode() == 200) {
            JsonArray matching = JsonParser.parseString(versionResp.body()).getAsJsonArray();
            if (!matching.isEmpty()) {
                LOGGER.info("Found GrimAC build declaring support for Minecraft " + mcVersion);
            } else {
                LOGGER.warning("No GrimAC Modrinth build declares Minecraft " + mcVersion
                        + " yet; falling back to latest (may not support this version)");
                versionResp = queryModrinth(client, baseVersionsUrl);
            }
        } else {
            versionResp = queryModrinth(client, baseVersionsUrl);
        }
        if (versionResp.statusCode() != 200) {
            throw new RuntimeException("Modrinth API returned HTTP " + versionResp.statusCode());
        }

        JsonArray versions = JsonParser.parseString(versionResp.body()).getAsJsonArray();
        if (versions.isEmpty()) {
            throw new RuntimeException("No Paper versions found on Modrinth for GrimAC");
        }

        // First entry is the latest
        JsonObject latest = versions.get(0).getAsJsonObject();
        String versionName = latest.get("version_number").getAsString();
        JsonArray files = latest.getAsJsonArray("files");

        // Find the primary file. Filenames come from a remote API response:
        // reject anything with path separators/relative segments so a
        // tampered response can't write outside plugins/.
        String downloadUrl = null;
        String fileName = null;
        String expectedSha1 = null;
        for (JsonElement fileEl : files) {
            JsonObject file = fileEl.getAsJsonObject();
            if (file.has("primary") && file.get("primary").getAsBoolean()) {
                downloadUrl = file.get("url").getAsString();
                fileName = file.get("filename").getAsString();
                expectedSha1 = extractSha1(file);
                break;
            }
        }
        // Fallback to first file
        if (downloadUrl == null && !files.isEmpty()) {
            JsonObject file = files.get(0).getAsJsonObject();
            downloadUrl = file.get("url").getAsString();
            fileName = file.get("filename").getAsString();
            expectedSha1 = extractSha1(file);
        }

        if (downloadUrl == null) {
            throw new RuntimeException("No downloadable file found for GrimAC " + versionName);
        }
        if (!isSafeFileName(fileName)) {
            throw new RuntimeException("Unsafe GrimAC filename from Modrinth: " + fileName);
        }

        LOGGER.info("Downloading GrimAC " + versionName + " (" + fileName + ")...");

        // Download the JAR
        HttpRequest dlReq = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .header("User-Agent", "Osmium/1.0 (github.com/djatlasv/Osmium)")
                .GET()
                .build();

        HttpResponse<InputStream> dlResp = client.send(dlReq, HttpResponse.BodyHandlers.ofInputStream());
        if (dlResp.statusCode() != 200) {
            throw new RuntimeException("Download failed: HTTP " + dlResp.statusCode());
        }

        File target = new File(pluginsDir, fileName);
        java.security.MessageDigest sha1;
        try {
            sha1 = java.security.MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
        try (InputStream rawIn = dlResp.body();
             java.security.DigestInputStream in = new java.security.DigestInputStream(rawIn, sha1);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int len;
            long total = 0;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
                total += len;
            }
            LOGGER.info("Downloaded " + (total / 1024) + " KB to plugins/" + fileName);
        }

        // Integrity: verify against Modrinth's published sha1 before the
        // jar is ever loaded. Tampered/corrupt downloads are deleted.
        String actualSha1 = hex(sha1.digest());
        if (expectedSha1 != null && !expectedSha1.equalsIgnoreCase(actualSha1)) {
            target.delete();
            throw new RuntimeException("GrimAC download failed sha1 verification (expected "
                    + expectedSha1 + ", got " + actualSha1 + ")");
        }

        LOGGER.info("GrimAC " + versionName + " installed. Restart the server to load it.");

        // Extract optimized default configs and sync webhook URL
        extractDefaultConfigs();
        syncWebhookUrl();
    }

    private static HttpResponse<String> queryModrinth(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Osmium/1.0 (github.com/djatlasv/Osmium)")
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String extractSha1(JsonObject file) {
        if (file.has("hashes") && file.getAsJsonObject("hashes").has("sha1")) {
            return file.getAsJsonObject("hashes").get("sha1").getAsString();
        }
        return null;
    }

    private static boolean isSafeFileName(String name) {
        if (name == null || name.isEmpty()) return false;
        return !name.contains("/") && !name.contains("\\") && !name.contains("..")
                && !name.startsWith(".");
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Syncs Osmium's discord-webhook.url into GrimAC's discord.yml so the
     * user only has to configure the webhook URL in one place (osmium.yml).
     */
    private static void syncWebhookUrl() {
        try {
            String osmiumUrl = org.osmium.OsmiumConfig.discordWebhookUrl;
            if (osmiumUrl == null || osmiumUrl.isBlank()) return;

            File discordYml = new File("plugins/GrimAC/discord.yml");
            if (!discordYml.exists()) return;

            String content = new String(java.nio.file.Files.readAllBytes(discordYml.toPath()));

            // Top-level keys only, single occurrence each: a global multiline
            // replaceAll would clobber other "enabled:" flags elsewhere in
            // the file. Replacement strings are quoteReplacement-escaped so
            // $ and \ in the URL can't be interpreted as regex groups.
            String yamlUrl = osmiumUrl.replace("\\", "\\\\").replace("\"", "\\\"");
            String updated = content
                    .replaceFirst("(?m)^webhook:.*$",
                            java.util.regex.Matcher.quoteReplacement("webhook: \"" + yamlUrl + "\""))
                    .replaceFirst("(?m)^enabled:.*$",
                            java.util.regex.Matcher.quoteReplacement("enabled: " + org.osmium.OsmiumConfig.discordWebhookEnabled));

            if (!updated.equals(content)) {
                java.nio.file.Files.write(discordYml.toPath(), updated.getBytes());
                LOGGER.info("Synced Osmium webhook URL to GrimAC discord.yml");
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not sync webhook URL to GrimAC", e);
        }
    }

    /**
     * Extracts Osmium's optimized GrimAC configs into plugins/GrimAC/
     * on first install. Only writes files that don't already exist.
     */
    private static void extractDefaultConfigs() {
        File grimConfigDir = new File("plugins/GrimAC");
        grimConfigDir.mkdirs();

        String[] configs = {"config.yml", "punishments.yml", "messages.yml", "discord.yml"};
        for (String name : configs) {
            File target = new File(grimConfigDir, name);
            if (target.exists()) continue;

            try (InputStream in = OsmiumGrimLoader.class.getResourceAsStream("/osmium-grim/" + name)) {
                if (in == null) {
                    LOGGER.warning("Missing bundled GrimAC config: " + name);
                    continue;
                }
                try (FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                LOGGER.info("Extracted GrimAC config: " + name);
            } catch (java.io.IOException e) {
                LOGGER.log(Level.WARNING, "Failed to extract GrimAC config: " + name, e);
            }
        }
    }
}
