package org.osmium.anticheat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osmium.OsmiumConfig;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cheat-client simulation at the anticheat's input boundaries: every test
 * fires the exact input a hostile client would send and asserts the
 * server-side defense holds. Covers chat-filter evasion (whitelist
 * smuggling, separators, homoglyphs/fullwidth), brand-attestation abuse
 * (payload flooding, missing hash, malformed fingerprints) and alt-tracker
 * flooding. Pure-logic level — no live protocol connection needed.
 */
public class AnticheatCheatClientSimTestSuite {

    @TempDir
    static Path tempDir;

    private static boolean filterEnabledBackup;

    @BeforeAll
    static void bootFilter() {
        // Fresh install: defaults written + active on first boot
        OsmiumChatFilter.init(tempDir.toFile());
        filterEnabledBackup = OsmiumConfig.chatFilterEnabled;
        OsmiumConfig.chatFilterEnabled = true;
    }

    @AfterAll
    static void restoreConfig() {
        OsmiumConfig.chatFilterEnabled = filterEnabledBackup;
    }

    @AfterEach
    void cleanSharedState() {
        OsmiumBrandEnforcement.pendingClients.clear();
        OsmiumBrandEnforcement.handshakeCompleted.clear();
        OsmiumBrandEnforcement.payloadSeen.clear();
        synchronized (OsmiumAltTracker.ipToUuids) { OsmiumAltTracker.ipToUuids.clear(); }
        synchronized (OsmiumAltTracker.fpToUuids) { OsmiumAltTracker.fpToUuids.clear(); }
    }

    // ------------------------------------------------------------------
    // Chat filter evasion (what a filtered-word bypass client sends)
    // ------------------------------------------------------------------

    @Test
    public void whitelistWordNoLongerSmugglesOtherMatches() {
        // Regression: one whitelisted word must not exempt the whole message
        assertNotNull(OsmiumChatFilter.check("fuck night"));
        assertNotNull(OsmiumChatFilter.check("shit classic"));
    }

    @Test
    public void separatorBypassCaught() {
        assertNotNull(OsmiumChatFilter.check("f.u.c.k"));
        assertNotNull(OsmiumChatFilter.check("s.h.1.t"));
        assertNotNull(OsmiumChatFilter.check("d.i.c.k"));
    }

    @Test
    public void separatedWordsRemainAllowed() {
        // Documented behavior: words stay separate — no cross-word matching
        assertNull(OsmiumChatFilter.check("f u c k"));
    }

    @Test
    public void homoglyphBypassCaught() {
        // Cyrillic lookalikes the old word-stripper silently deleted
        assertNotNull(OsmiumChatFilter.check("n\u0456gger"), "Cyrillic і must fold to i");
        assertNotNull(OsmiumChatFilter.check("fu\u0441k"), "Cyrillic с must fold to c");
        assertNotNull(OsmiumChatFilter.check("b\u0456tch"), "Cyrillic і in bit(ch)");
    }

    @Test
    public void fullwidthBypassCaught() {
        assertNotNull(OsmiumChatFilter.check("ｆｕｃｋ"));
        assertNotNull(OsmiumChatFilter.check("ＦＵＣＫ")); // lowercased then NFKC-folded
        assertNotNull(OsmiumChatFilter.check("ＳＨＩＴ"));
    }

    @Test
    public void multiWordPatternsStillMatch() {
        // The span-scoped whitelist fix must not break multi-word regexes
        assertNotNull(OsmiumChatFilter.check("kill yourself"));
        assertNotNull(OsmiumChatFilter.check("please kill yourself night"));
        assertNotNull(OsmiumChatFilter.check("go kill"));
    }

    @Test
    public void wordsWithoutPatternsPass() {
        // bare "ass" matches no default pattern — must not be flagged
        assertNull(OsmiumChatFilter.check("ass night"));
    }

    @Test
    public void legitWordsPass() {
        assertNull(OsmiumChatFilter.check("good night everyone"));
        assertNull(OsmiumChatFilter.check("what a classic"));
        assertNull(OsmiumChatFilter.check("my digger is bigger"));
        assertNull(OsmiumChatFilter.check("I visited Scunthorpe"));
        assertNull(OsmiumChatFilter.check("cocktail hour"));
        assertNull(OsmiumChatFilter.check("the assassin class"));
    }

    @Test
    public void filterActiveOnFirstBoot() {
        // Regression: load() used to clear the just-loaded defaults, leaving
        // the filter dead until the next config reload
        File fresh = tempDir.resolve("fresh-install").toFile();
        fresh.mkdirs();
        OsmiumChatFilter.reload(); // reset in-memory lists, re-init with a virgin dir
        File dataFile = new File(fresh, "osmium-words.json");
        // point the filter at the virgin dir and init again
        OsmiumChatFilter.init(fresh);
        assertTrue(dataFile.exists(), "defaults must be written on first boot");
        assertNotNull(OsmiumChatFilter.check("fuck"), "filter must be active on first boot");
    }

    // ------------------------------------------------------------------
    // Brand enforcement (HandShaker payload abuse)
    // ------------------------------------------------------------------

    private static final String MODS = "fabric,hand-shaker";

    @Test
    public void validFingerprintsAccepted() {
        String hex = repeat('a', 60) + "1234";
        assertTrue(OsmiumBrandEnforcement.isValidFingerprint(hex));
        assertTrue(OsmiumBrandEnforcement.isValidFingerprint(hex.toUpperCase()));
    }

    @Test
    public void malformedFingerprintsRejected() {
        // A hostile client flooding random garbage must not pollute the
        // fingerprint store — only 64-char hex is accepted
        assertFalse(OsmiumBrandEnforcement.isValidFingerprint(null));
        assertFalse(OsmiumBrandEnforcement.isValidFingerprint(""));
        assertFalse(OsmiumBrandEnforcement.isValidFingerprint(repeat('a', 63)));
        assertFalse(OsmiumBrandEnforcement.isValidFingerprint(repeat('a', 65)));
        assertFalse(OsmiumBrandEnforcement.isValidFingerprint(repeat('g', 64)));
        assertFalse(OsmiumBrandEnforcement.isValidFingerprint("unknown" + repeat('0', 57)));
        assertFalse(OsmiumBrandEnforcement.isValidFingerprint("../../etc/passwd"));
    }

    @Test
    public void missingOrWrongHashNeverPasses() {
        // A client that omits the hash field must not skip verification
        assertTrue(OsmiumBrandEnforcement.verifyHash(MODS,
                OsmiumBrandEnforcement.sha256ForTest(MODS)));
        assertFalse(OsmiumBrandEnforcement.verifyHash(MODS, null));
        assertFalse(OsmiumBrandEnforcement.verifyHash(MODS, ""));
        assertFalse(OsmiumBrandEnforcement.verifyHash(MODS, repeat('0', 64)));
        // Tampered mod list with the ORIGINAL hash must not pass
        assertFalse(OsmiumBrandEnforcement.verifyHash(MODS + ",krloader",
                OsmiumBrandEnforcement.sha256ForTest(MODS)));
    }

    @Test
    public void onlyFirstPayloadIsProcessed() {
        UUID client = UUID.randomUUID();
        assertTrue(OsmiumBrandEnforcement.isFirstPayload(client));
        OsmiumBrandEnforcement.pendingClients.put(client, Set.of("fabric"));
        assertFalse(OsmiumBrandEnforcement.isFirstPayload(client),
                "payload replay after the first must be ignored (mod-list flapping / flood)");
        OsmiumBrandEnforcement.pendingClients.remove(client);
        OsmiumBrandEnforcement.handshakeCompleted.add(client);
        assertFalse(OsmiumBrandEnforcement.isFirstPayload(client));
    }

    @Test
    public void rejectedPayloadsAlsoCountAsSeen() {
        // Live-fire finding: a flood of malformed payloads was never
        // rate-limited because rejected payloads never marked the client
        // seen — decode + sha256 + log spam per packet
        UUID client = UUID.randomUUID();
        OsmiumBrandEnforcement.payloadSeen.add(client);
        assertFalse(OsmiumBrandEnforcement.isFirstPayload(client),
                "flooded rejected payloads must be dropped before decoding");
    }

    // ------------------------------------------------------------------
    // Alt tracker flood (fingerprint spam → unbounded store growth)
    // ------------------------------------------------------------------

    @Test
    public void fingerprintFloodIsBounded() {
        UUID victim = UUID.randomUUID();
        for (int i = 0; i < 25_000; i++) {
            OsmiumAltTracker.recordAssociation(OsmiumAltTracker.fpToUuids, "fp-" + i, victim.toString());
        }
        assertTrue(OsmiumAltTracker.fpToUuids.size() <= OsmiumAltTracker.MAX_TRACKED_ENTRIES,
                "flood must be capped, got " + OsmiumAltTracker.fpToUuids.size());
    }

    @Test
    public void floodDoesNotEvictActivePlayerData() {
        for (int i = 0; i < 300; i++) {
            OsmiumAltTracker.recordAssociation(OsmiumAltTracker.fpToUuids, "hot-fingerprint", UUID.randomUUID().toString());
        }
        // small flood (< cap): nothing evicted, history fully preserved
        for (int i = 0; i < 5_000; i++) {
            OsmiumAltTracker.recordAssociation(OsmiumAltTracker.fpToUuids, "small-" + i, UUID.randomUUID().toString());
        }
        Set<String> linked = null;
        synchronized (OsmiumAltTracker.fpToUuids) {
            Set<String> stored = OsmiumAltTracker.fpToUuids.get("hot-fingerprint");
            linked = stored == null ? null : Set.copyOf(stored);
        }
        assertNotNull(linked, "sub-cap flood must not evict anything");
        assertEquals(300, linked.size(), "all associations preserved under the cap");

        // repeated floods at full packet rate: cap holds, and an active
        // player's fingerprint (re-recorded on each join) survives
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 25_000; i++) {
                OsmiumAltTracker.recordAssociation(OsmiumAltTracker.fpToUuids, "fp-" + round + "-" + i, UUID.randomUUID().toString());
            }
            OsmiumAltTracker.recordAssociation(OsmiumAltTracker.fpToUuids, "hot-fingerprint", UUID.randomUUID().toString());
        }
        assertTrue(OsmiumAltTracker.fpToUuids.size() <= OsmiumAltTracker.MAX_TRACKED_ENTRIES,
                "flood must be capped, got " + OsmiumAltTracker.fpToUuids.size());
        synchronized (OsmiumAltTracker.fpToUuids) {
            assertNotNull(OsmiumAltTracker.fpToUuids.get("hot-fingerprint"),
                    "actively-used fingerprint must survive flood rounds");
        }
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }
}
