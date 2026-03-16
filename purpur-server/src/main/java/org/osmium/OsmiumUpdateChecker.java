package org.osmium;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Level;

/**
 * Checks the GitHub API for the latest commit on osmium/main and
 * compares it to the running server version. Runs async so it
 * doesn't block startup.
 */
public class OsmiumUpdateChecker {

    private static final String REPO = "djatlasv/Osmium";
    private static final String BRANCH = "osmium/main";
    private static final String API_URL =
            "https://api.github.com/repos/" + REPO + "/commits/" + BRANCH;

    public static void checkAsync() {
        Thread.ofVirtual().name("Osmium-UpdateChecker").start(() -> {
            try {
                Bukkit.getLogger().info("[Osmium] Checking for updates...");
                check();
            } catch (Exception e) {
                Bukkit.getLogger().warning("[Osmium] Update check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (e.getCause() != null) {
                    Bukkit.getLogger().warning("[Osmium]   Caused by: " + e.getCause());
                }
            }
        });
    }

    private static void check() throws Exception {
        // Get running version from server brand string (contains commit hash)
        String serverVersion = Bukkit.getVersion(); // e.g. "1.21.11-DEV-osmium/main@ca2ea07 (...)"
        Bukkit.getLogger().info("[Osmium] Server version string: " + serverVersion);
        String runningHash = extractHash(serverVersion);
        if (runningHash == null) {
            Bukkit.getLogger().warning("[Osmium] Could not extract commit hash from version string");
            return;
        }
        Bukkit.getLogger().info("[Osmium] Running commit: " + runningHash);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("User-Agent", "Osmium/1.0")
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Bukkit.getLogger().info("[Osmium] GitHub API response: HTTP " + response.statusCode());
        if (response.statusCode() != 200) {
            Bukkit.getLogger().warning("[Osmium] GitHub API returned non-200: " + response.body().substring(0, Math.min(200, response.body().length())));
            return;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String latestHash = json.get("sha").getAsString();
        String shortLatest = latestHash.substring(0, 7);
        String shortRunning = runningHash.length() >= 7 ? runningHash.substring(0, 7) : runningHash;

        if (latestHash.startsWith(runningHash) || runningHash.startsWith(shortLatest)) {
            Bukkit.getLogger().info("[Osmium] You are up to date! (" + shortRunning + ")");
        } else {
            Bukkit.getLogger().warning("[Osmium] Update available! Running: " + shortRunning + " | Latest: " + shortLatest);
            Bukkit.getLogger().warning("[Osmium] Download at: https://github.com/" + REPO);
        }
    }

    private static String extractHash(String version) {
        // Format: "1.21.11-DEV-osmium/main@HASH (...)" or similar
        int at = version.indexOf('@');
        if (at == -1) return null;
        int end = version.indexOf(' ', at);
        if (end == -1) end = version.indexOf(')', at);
        if (end == -1) end = version.length();
        return version.substring(at + 1, end);
    }
}
