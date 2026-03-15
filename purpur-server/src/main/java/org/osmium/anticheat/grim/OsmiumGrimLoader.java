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
    private static final String MODRINTH_VERSIONS_URL =
            "https://api.modrinth.com/v2/project/" + MODRINTH_PROJECT + "/version?loaders=%5B%22paper%22%5D";

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

    /** No-op — GrimAC starts itself as a plugin via Paper's loader. */
    public static void start() {}

    /** No-op — GrimAC stops itself as a plugin via Paper's loader. */
    public static void stop() {}

    private static void downloadLatestFromModrinth(File pluginsDir) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        // Query Modrinth for latest Paper-compatible version
        HttpRequest versionReq = HttpRequest.newBuilder()
                .uri(URI.create(MODRINTH_VERSIONS_URL))
                .header("User-Agent", "Osmium/1.0 (github.com/djatlasv/Osmium)")
                .GET()
                .build();

        HttpResponse<String> versionResp = client.send(versionReq, HttpResponse.BodyHandlers.ofString());
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

        // Find the primary file
        String downloadUrl = null;
        String fileName = null;
        for (JsonElement fileEl : files) {
            JsonObject file = fileEl.getAsJsonObject();
            if (file.has("primary") && file.get("primary").getAsBoolean()) {
                downloadUrl = file.get("url").getAsString();
                fileName = file.get("filename").getAsString();
                break;
            }
        }
        // Fallback to first file
        if (downloadUrl == null && !files.isEmpty()) {
            JsonObject file = files.get(0).getAsJsonObject();
            downloadUrl = file.get("url").getAsString();
            fileName = file.get("filename").getAsString();
        }

        if (downloadUrl == null) {
            throw new RuntimeException("No downloadable file found for GrimAC " + versionName);
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
        try (InputStream in = dlResp.body();
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

        LOGGER.info("GrimAC " + versionName + " installed. Restart the server to load it.");
    }
}
