package org.osmium;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds per-player identity data captured during the configuration phase
 * (known packs), for play-phase validation once Bukkit/ViaVersion state is
 * reliable. Entries are short-lived and removed after use.
 */
public final class OsmiumIdentity {

    private OsmiumIdentity() {}

    private static final Map<UUID, List<String[]>> KNOWN_PACKS = new ConcurrentHashMap<>();

    public static void storeKnownPacks(UUID player, List<String[]> packs) {
        // packs: [namespace, id, version]
        KNOWN_PACKS.put(player, packs);
    }

    public static List<String[]> takeKnownPacks(UUID player) {
        List<String[]> packs = KNOWN_PACKS.remove(player);
        return packs == null ? List.of() : packs;
    }

    public static void clear(UUID player) {
        KNOWN_PACKS.remove(player);
    }
}
