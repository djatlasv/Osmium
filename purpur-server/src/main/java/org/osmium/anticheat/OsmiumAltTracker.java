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
 * Tracks IP-to-UUID and fingerprint-to-UUID associations for alt account detection.
 * Fingerprints are hardware-based hashes sent by the HandShaker client mod.
 * Persists data to osmium-ips.json and osmium-fingerprints.json.
 */
public class OsmiumAltTracker {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, Set<String>>>() {}.getType();

    private static File ipDataFile;
    private static File fpDataFile;
    // IP -> Set of UUID strings
    private static final Map<String, Set<String>> ipToUuids = new ConcurrentHashMap<>();
    // Fingerprint -> Set of UUID strings
    private static final Map<String, Set<String>> fpToUuids = new ConcurrentHashMap<>();

    public static void init(File serverDir) {
        ipDataFile = new File(serverDir, "osmium-ips.json");
        fpDataFile = new File(serverDir, "osmium-fingerprints.json");
        loadMap(ipDataFile, ipToUuids);
        loadMap(fpDataFile, fpToUuids);
    }

    private static void loadMap(File file, Map<String, Set<String>> target) {
        if (file == null || !file.exists()) return;

        try (FileReader reader = new FileReader(file)) {
            Map<String, Set<String>> loaded = GSON.fromJson(reader, DATA_TYPE);
            if (loaded != null) {
                target.clear();
                loaded.forEach((key, uuids) -> {
                    Set<String> set = ConcurrentHashMap.newKeySet();
                    set.addAll(uuids);
                    target.put(key, set);
                });
            }
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.WARNING, "Could not load " + file.getName(), ex);
        }
    }

    private static void saveMap(File file, Map<String, Set<String>> source) {
        if (file == null) return;

        Map<String, Set<String>> serializable = new HashMap<>();
        source.forEach((key, uuids) -> serializable.put(key, new HashSet<>(uuids)));

        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(serializable, DATA_TYPE, writer);
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.WARNING, "Could not save " + file.getName(), ex);
        }
    }

    /**
     * Records a player's IP association. Call on successful join.
     */
    public static void recordJoin(String ip, UUID uuid) {
        String uuidStr = uuid.toString();
        ipToUuids.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet()).add(uuidStr);
        saveMap(ipDataFile, ipToUuids);
    }

    /**
     * Records a player's hardware fingerprint association.
     * Called from OsmiumBrandEnforcement when the HandShaker payload arrives.
     */
    public static void recordFingerprint(String fingerprint, UUID uuid) {
        String uuidStr = uuid.toString();
        fpToUuids.computeIfAbsent(fingerprint, k -> ConcurrentHashMap.newKeySet()).add(uuidStr);
        saveMap(fpDataFile, fpToUuids);
    }

    /**
     * Returns the set of UUIDs that have connected from the given IP.
     */
    public static Set<UUID> getUuidsForIp(String ip) {
        return toUuidSet(ipToUuids.get(ip));
    }

    /**
     * Returns the set of UUIDs that share the given hardware fingerprint.
     */
    public static Set<UUID> getUuidsForFingerprint(String fingerprint) {
        return toUuidSet(fpToUuids.get(fingerprint));
    }

    /**
     * Returns the fingerprints associated with a UUID (for lookup during join).
     */
    public static Set<String> getFingerprintsForUuid(UUID uuid) {
        String uuidStr = uuid.toString();
        Set<String> result = new HashSet<>();
        fpToUuids.forEach((fp, uuids) -> {
            if (uuids.contains(uuidStr)) result.add(fp);
        });
        return result;
    }

    private static Set<UUID> toUuidSet(Set<String> uuidStrs) {
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
