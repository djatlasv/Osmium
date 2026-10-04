package org.osmium;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cheat-client simulation for the non-anticheat-package features:
 * /report store flooding, /sethome name injection, and GUI click spoofing
 * (the stale-flags-after-death vector). Pure-logic level.
 */
public class OsmiumCheatClientSimTestSuite {

    @AfterEach
    void cleanSharedState() {
        OsmiumReport.REPORTS.clear();
    }

    // ------------------------------------------------------------------
    // /report store flooding
    // ------------------------------------------------------------------

    @Test
    public void reportStoreFloodIsCapped() {
        long base = System.currentTimeMillis();
        for (int i = 0; i < OsmiumReport.MAX_STORED_REPORTS + 50; i++) {
            UUID id = UUID.randomUUID();
            OsmiumReport.REPORTS.put(id, new OsmiumReport.Report(
                    id, "spam-" + i, UUID.randomUUID(),
                    "target", UUID.randomUUID(), "spam", base + i,
                    "open", "", 0));
        }
        OsmiumReport.evictOldest();
        assertTrue(OsmiumReport.REPORTS.size() <= OsmiumReport.MAX_STORED_REPORTS,
                "store must be capped, got " + OsmiumReport.REPORTS.size());
        // newest must survive: the 50 oldest (spam-0..49 by createdAt) gone
        boolean anyOldestSurvivor = OsmiumReport.REPORTS.values().stream()
                .anyMatch(r -> r.reason().startsWith("spam-0"));
        assertFalse(anyOldestSurvivor, "oldest reports must be evicted, not newest");
    }

    // ------------------------------------------------------------------
    // /sethome name injection
    // ------------------------------------------------------------------

    @Test
    public void validHomeNamesAccepted() {
        assertTrue(OsmiumHomes.isValidHomeName("home"));
        assertTrue(OsmiumHomes.isValidHomeName("base-2"));
        assertTrue(OsmiumHomes.isValidHomeName("mine_entrance"));
        assertTrue(OsmiumHomes.isValidHomeName("a".repeat(32)));
    }

    @Test
    public void hostileHomeNamesRejected() {
        assertFalse(OsmiumHomes.isValidHomeName(""), "empty");
        assertFalse(OsmiumHomes.isValidHomeName("a".repeat(33)), "over cap");
        assertFalse(OsmiumHomes.isValidHomeName("my home"), "space");
        assertFalse(OsmiumHomes.isValidHomeName("\u00a7cred"), "format-code injection into /homes echo");
        assertFalse(OsmiumHomes.isValidHomeName("дом"), "non-ascii homoglyph games");
        assertFalse(OsmiumHomes.isValidHomeName("\u0000x"), "control char into JSON store");
        assertFalse(OsmiumHomes.isValidHomeName("\n"), "newline into JSON store");
    }

    // ------------------------------------------------------------------
    // GUI click spoofing (containerId validation)
    // ------------------------------------------------------------------

    @Test
    public void staleContainerIdsRejected() {
        // recorded id = 2 (GUI as opened); after a server-side close (death)
        // the live menu resets to the inventory (id 0):
        assertFalse(OsmiumRtp.clickMatchesOpenGui(2, 0, 0),
                "stale flags + inventory click must not fire GUI actions");
        // client spoofs the OLD container id after respawn:
        assertFalse(OsmiumRtp.clickMatchesOpenGui(2, 2, 0),
                "spoofed packet id must be checked against the LIVE menu");
        // never opened a GUI:
        assertFalse(OsmiumRtp.clickMatchesOpenGui(null, 2, 2));
        assertFalse(OsmiumRtp.clickMatchesOpenGui(2, 2, null));
        // legit click on the open Osmium GUI:
        assertTrue(OsmiumRtp.clickMatchesOpenGui(2, 2, 2));
        // wrong GUI (recorded=3, clicked=2):
        assertFalse(OsmiumRtp.clickMatchesOpenGui(3, 2, 2));
    }
}
