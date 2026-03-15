package org.osmium.anticheat.grim;

import org.bukkit.Bukkit;

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
 * Downloads from the GrimAC GitHub releases if not already present.
 * Paper's plugin loader handles the rest — no embedded shim needed.
 */
public class OsmiumGrimLoader {

    private static final Logger LOGGER = Logger.getLogger("Osmium-GrimAC");
    private static final String GRIM_JAR = "GrimAC.jar";
    private static final String DOWNLOAD_URL = "https://github.com/GrimAnticheat/Grim/releases/download/2.3.74/GrimAC-2.3.74.jar";

    /**
     * Called during server init if grim.enabled is true.
     * Ensures GrimAC plugin JAR exists in ./plugins/, downloading if needed.
     */
    public static void init() {
        File pluginsDir = new File("plugins");
        pluginsDir.mkdirs();

        File grimJar = new File(pluginsDir, GRIM_JAR);

        // Also check for any existing grim jar with a different name
        if (!grimJar.exists()) {
            File[] existing = pluginsDir.listFiles((dir, name) ->
                    name.toLowerCase().startsWith("grim") && name.endsWith(".jar"));
            if (existing != null && existing.length > 0) {
                LOGGER.info("Found existing GrimAC JAR: " + existing[0].getName());
                return;
            }
        }

        if (grimJar.exists()) {
            LOGGER.info("GrimAC plugin JAR found at plugins/" + GRIM_JAR);
            return;
        }

        LOGGER.info("GrimAC not found in plugins/. Downloading...");
        try {
            downloadGrimAC(grimJar);
            LOGGER.info("GrimAC downloaded successfully to plugins/" + GRIM_JAR);
            LOGGER.info("GrimAC will load on next server restart.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to download GrimAC. Download it manually from: " + DOWNLOAD_URL, e);
        }
    }

    /**
     * No-op — GrimAC starts itself as a plugin via Paper's loader.
     */
    public static void start() {
        // Plugin lifecycle handled by Paper
    }

    /**
     * No-op — GrimAC stops itself as a plugin via Paper's loader.
     */
    public static void stop() {
        // Plugin lifecycle handled by Paper
    }

    private static void downloadGrimAC(File target) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DOWNLOAD_URL))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + " downloading GrimAC");
        }

        try (InputStream in = response.body();
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int len;
            long total = 0;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
                total += len;
            }
            LOGGER.info("Downloaded " + (total / 1024) + " KB");
        }
    }
}
