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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    // Insertion-order maps with a hard cap: every rotating/mobile IP a
    // player ever used would otherwise be kept (and persisted) forever.
    // All access is synchronized — recording happens on joins only, so the
    // lock is uncontended in practice.
    private static final int MAX_TRACKED_ENTRIES = 20_000;
    private static final Map<String, Set<String>> ipToUuids = new LinkedHashMap<>();
    private static final Map<String, Set<String>> fpToUuids = new LinkedHashMap<>();

    // Debounced saving: joins/fingerprint events only mark dirty; a single
    // background task flushes both files every few seconds. Prevents
    // synchronous main-thread disk I/O during mass join waves.
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean();
    private static final ScheduledExecutorService SAVER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Osmium-AltTracker-Saver");
        t.setDaemon(true);
        return t;
    });

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
                synchronized (target) {
                    target.clear();
                    loaded.forEach((key, uuids) -> {
                        if (key != null && uuids != null) {
                            target.put(key, new HashSet<>(uuids));
                        }
                    });
                }
            }
        } catch (IOException ex) {
            Bukkit.getLogger().log(Level.WARNING, "Could not load " + file.getName(), ex);
        }
    }

    private static void saveMap(File file, Map<String, Set<String>> source) {
        if (file == null) return;

        Map<String, Set<String>> serializable;
        synchronized (source) {
            serializable = new HashMap<>();
            source.forEach((key, uuids) -> serializable.put(key, new HashSet<>(uuids)));
        }

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
        recordAssociation(ipToUuids, ip, uuid.toString());
        scheduleFlush();
    }

    /**
     * Marks data dirty and schedules one background flush. Never blocks.
     */
    private static void scheduleFlush() {
        if (FLUSH_SCHEDULED.compareAndSet(false, true)) {
            SAVER.schedule(() -> {
                try {
                    flush();
                } catch (Exception e) {
                    Bukkit.getLogger().log(Level.WARNING, "Alt tracker flush failed", e);
                } finally {
                    FLUSH_SCHEDULED.set(false);
                }
            }, 5, TimeUnit.SECONDS);
        }
    }

    /**
     * Writes both data files now. Safe to call from any thread.
     */
    public static void flush() {
        saveMap(ipDataFile, ipToUuids);
        saveMap(fpDataFile, fpToUuids);
    }

    /**
     * Records a player's hardware fingerprint association.
     * Called from OsmiumBrandEnforcement when the HandShaker payload arrives.
     */
    public static void recordFingerprint(String fingerprint, UUID uuid) {
        recordAssociation(fpToUuids, fingerprint, uuid.toString());
        scheduleFlush();
    }

    /**
     * Adds a key -> uuid association, evicting oldest entries (insertion
     * order) once the map exceeds the hard cap.
     */
    private static void recordAssociation(Map<String, Set<String>> map, String key, String uuidStr) {
        synchronized (map) {
            map.computeIfAbsent(key, k -> new HashSet<>()).add(uuidStr);
            while (map.size() > MAX_TRACKED_ENTRIES) {
                Iterator<String> it = map.keySet().iterator();
                if (!it.hasNext()) break;
                it.next();
                it.remove();
            }
        }
    }

    /**
     * Returns the set of UUIDs that have connected from the given IP.
     */
    public static Set<UUID> getUuidsForIp(String ip) {
        Set<String> snapshot;
        synchronized (ipToUuids) {
            Set<String> stored = ipToUuids.get(ip);
            snapshot = stored == null ? null : new HashSet<>(stored);
        }
        return toUuidSet(snapshot);
    }

    /**
     * Returns the set of UUIDs that share the given hardware fingerprint.
     */
    public static Set<UUID> getUuidsForFingerprint(String fingerprint) {
        Set<String> snapshot;
        synchronized (fpToUuids) {
            Set<String> stored = fpToUuids.get(fingerprint);
            snapshot = stored == null ? null : new HashSet<>(stored);
        }
        return toUuidSet(snapshot);
    }

    /**
     * Returns the fingerprints associated with a UUID (for lookup during join).
     */
    public static Set<String> getFingerprintsForUuid(UUID uuid) {
        String uuidStr = uuid.toString();
        Set<String> result = new HashSet<>();
        synchronized (fpToUuids) {
            fpToUuids.forEach((fp, uuids) -> {
                if (uuids.contains(uuidStr)) result.add(fp);
            });
        }
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
