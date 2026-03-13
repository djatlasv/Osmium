package org.osmium.anticheat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Tracks IP-to-UUID associations for alt account detection.
 * Persists data to osmium-ips.json in the server root directory.
 */
public class OsmiumAltTracker {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, Set<String>>>() {}.getType();

    private static File dataFile;
    // IP -> Set of UUID strings
    private static final Map<String, Set<String>> ipToUuids = new ConcurrentHashMap<>();

    public static void init(File serverDir) {
        dataFile = new File(serverDir, "osmium-ips.json");
        load();
    }

    private static void load() {
        if (dataFile == null || !dataFile.exists()) return;

        try (FileReader reader = new FileReader(dataFile)) {
            Map<String, Set<String>> loaded = GSON.fromJson(reader, DATA_TYPE);
            if (loaded != null) {
                ipToUuids.clear();
                loaded.forEach((ip, uuids) -> ipToUuids.put(ip, ConcurrentHashMap.newKeySet()));
                loaded.forEach((ip, uuids) -> ipToUuids.get(ip).addAll(uuids));
            }
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.WARNING, "Could not load osmium-ips.json", ex);
        }
    }

    private static void save() {
        if (dataFile == null) return;

        // Convert ConcurrentHashMap.KeySetView to regular HashSet for serialization
        Map<String, Set<String>> serializable = new HashMap<>();
        ipToUuids.forEach((ip, uuids) -> serializable.put(ip, new HashSet<>(uuids)));

        try (FileWriter writer = new FileWriter(dataFile)) {
            GSON.toJson(serializable, DATA_TYPE, writer);
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.WARNING, "Could not save osmium-ips.json", ex);
        }
    }

    /**
     * Records a player's IP association. Call on successful join.
     */
    public static void recordJoin(String ip, UUID uuid) {
        String uuidStr = uuid.toString();
        ipToUuids.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet()).add(uuidStr);
        save();
    }

    /**
     * Returns the set of UUIDs that have connected from the given IP.
     */
    public static Set<UUID> getUuidsForIp(String ip) {
        Set<String> uuidStrs = ipToUuids.get(ip);
        if (uuidStrs == null || uuidStrs.isEmpty()) return Collections.emptySet();

        Set<UUID> result = new HashSet<>();
        for (String str : uuidStrs) {
            try {
                result.add(UUID.fromString(str));
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }
}
