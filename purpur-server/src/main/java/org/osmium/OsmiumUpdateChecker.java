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
                check();
            } catch (Exception ignored) {
            }
        });
    }

    private static void check() throws Exception {
        // Get running commit hash from Paper's ServerBuildInfo
        io.papermc.paper.ServerBuildInfo buildInfo = io.papermc.paper.ServerBuildInfo.buildInfo();
        String runningHash = buildInfo.gitCommit().orElse(null);
        if (runningHash == null) {
            Bukkit.getLogger().warning("[Osmium] Could not determine running commit hash");
            return;
        }

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
        if (response.statusCode() != 200) return;

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String latestHash = json.get("sha").getAsString();
        String shortLatest = latestHash.substring(0, 7);
        String shortRunning = runningHash.substring(0, Math.min(7, runningHash.length()));

        if (latestHash.startsWith(runningHash) || runningHash.startsWith(latestHash)) {
            Bukkit.getLogger().info("[Osmium] You are up to date! (" + shortRunning + ")");
        } else {
            Bukkit.getLogger().warning("[Osmium] Update available! Running: " + shortRunning + " | Latest: " + shortLatest);
            Bukkit.getLogger().warning("[Osmium] Download at: https://github.com/" + REPO);
        }
    }
}
