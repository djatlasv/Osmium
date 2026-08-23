package org.osmium;

import org.junit.jupiter.api.Test;
import org.osmium.discord.OsmiumDiscordBot;

import static org.junit.jupiter.api.Assertions.*;

/** Permission level ordering/parse tests for the Discord bot ladder. */
public class DiscordPermissionLevelTest {

    @Test
    public void rankOrderingIsStrict() {
        var levels = OsmiumDiscordBot.Level.values();
        for (int i = 1; i < levels.length; i++) {
            assertTrue(levels[i].rank > levels[i - 1].rank,
                    levels[i] + " must outrank " + levels[i - 1]);
        }
    }

    @Test
    public void parseIsCaseInsensitiveAndSafe() {
        assertEquals(OsmiumDiscordBot.Level.MOD, OsmiumDiscordBot.Level.of("mod"));
        assertEquals(OsmiumDiscordBot.Level.MOD, OsmiumDiscordBot.Level.of("MOD"));
        assertEquals(OsmiumDiscordBot.Level.NONE, OsmiumDiscordBot.Level.of("bogus"));
        assertEquals(OsmiumDiscordBot.Level.NONE, OsmiumDiscordBot.Level.of(null));
    }

    @Test
    public void viewerCannotSatisfyAdminRequirement() {
        assertTrue(OsmiumDiscordBot.Level.ADMIN.rank > OsmiumDiscordBot.Level.VIEWER.rank);
        assertFalse(OsmiumDiscordBot.Level.VIEWER.rank >= OsmiumDiscordBot.Level.HELPER.rank);
    }
}
