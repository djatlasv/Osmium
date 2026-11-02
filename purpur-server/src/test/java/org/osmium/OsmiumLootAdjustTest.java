package org.osmium;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.loot.LootTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OsmiumLootAdjust: apple drop multiplier applied to leaves loot tables at
 * registry-load time. The JSON transform must scale ONLY the apple pool's
 * table_bonus chances (sapling/stick pools untouched), clamp at 1.0, and
 * leave every other loot table byte-identical.
 */
public class OsmiumLootAdjustTest {

    private static final ResourceKey<LootTable> OAK_LEAVES =
            ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("blocks/oak_leaves"));
    private static final ResourceKey<LootTable> GRASS_BLOCK =
            ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("blocks/grass_block"));

    private static JsonObject loadVanillaTable() throws Exception {
        try (var in = OsmiumLootAdjustTest.class.getResourceAsStream(
                "/data/minecraft/loot_table/blocks/oak_leaves.json")) {
            assertNotNull(in, "vanilla oak_leaves loot table must be on the test classpath");
            return JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject();
        }
    }

    @Test
    public void applePoolChancesScaledOtherPoolsUntouched() throws Exception {
        JsonObject vanilla = loadVanillaTable();
        JsonElement scaled = OsmiumLootAdjust.transform(OAK_LEAVES, vanilla.deepCopy());

        JsonObject before = loadVanillaTable();
        List<String> appleChancesBefore = tableBonusChances(before);
        List<String> appleChancesAfter = tableBonusChances(scaled.getAsJsonObject());
        assertEquals(appleChancesBefore.size(), appleChancesAfter.size());
        for (int i = 0; i < appleChancesBefore.size(); i++) {
            double was = Double.parseDouble(appleChancesBefore.get(i));
            double now = Double.parseDouble(appleChancesAfter.get(i));
            assertEquals(was * 3.0, now, 1e-9, "apple chance tier must be multiplied by 3");
        }

        // non-apple pools (sapling, stick) must be byte-identical to vanilla
        JsonObject scaledJson = scaled.getAsJsonObject();
        assertPoolEquals(before, scaledJson, 0);
        assertPoolEquals(before, scaledJson, 1);
    }

    @Test
    public void multiplierOneLeavesTableUntouched() throws Exception {
        double backup = OsmiumConfig.applesDropMultiplier;
        try {
            OsmiumConfig.applesDropMultiplier = 1.0;
            JsonObject vanilla = loadVanillaTable();
            JsonElement out = OsmiumLootAdjust.transform(OAK_LEAVES, vanilla.deepCopy());
            assertEquals(vanilla.toString(), out.toString(), "multiplier 1.0 must not touch the table");
        } finally {
            OsmiumConfig.applesDropMultiplier = backup;
        }
    }

    @Test
    public void nonLeafTablesUnchanged() throws Exception {
        JsonObject vanilla = loadVanillaTable();
        JsonElement out = OsmiumLootAdjust.transform(GRASS_BLOCK, vanilla.deepCopy());
        assertEquals(vanilla.toString(), out.toString());
    }

    @Test
    public void nonLootRegistriesUnchanged() throws Exception {
        ResourceKey<?> otherRegistry = ResourceKey.create(
                Registries.BIOME, Identifier.withDefaultNamespace("blocks/oak_leaves"));
        JsonObject vanilla = loadVanillaTable();
        JsonElement out = OsmiumLootAdjust.transform(otherRegistry, vanilla.deepCopy());
        assertEquals(vanilla.toString(), out.toString());
    }

    @Test
    public void malformedJsonFailsOpen() {
        JsonObject garbage = new JsonObject();
        garbage.addProperty("pools", "not-an-array");
        JsonElement out = OsmiumLootAdjust.transform(OAK_LEAVES, garbage);
        assertEquals(garbage.toString(), out.toString(), "parse surprises must fail open (vanilla table passes through)");
    }

    private static List<String> tableBonusChances(JsonObject table) {
        List<String> out = new ArrayList<>();
        for (JsonElement pool : table.getAsJsonArray("pools")) {
            if (!poolContainsApple(pool)) continue;
            collectTableBonusChances(pool, out);
        }
        return out;
    }

    private static void assertPoolEquals(JsonObject before, JsonObject after, int index) {
        JsonArray beforePools = before.getAsJsonArray("pools");
        JsonArray afterPools = after.getAsJsonArray("pools");
        assertEquals(beforePools.get(index).toString(), afterPools.get(index).toString(),
                "non-apple pool " + index + " must stay vanilla");
    }

    private static boolean poolContainsApple(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.entrySet().stream().anyMatch(e -> e.getKey().equals("type"))
                    && obj.has("name") && obj.get("name").isJsonPrimitive()
                    && "minecraft:apple".equals(obj.get("name").getAsString())
                    && obj.has("type") && "minecraft:item".equals(obj.get("type").getAsString())) {
                return true;
            }
            for (var e : obj.entrySet()) {
                if (poolContainsApple(e.getValue())) return true;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (poolContainsApple(child)) return true;
            }
        }
        return false;
    }

    private static void collectTableBonusChances(JsonElement element, List<String> out) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("type") && "minecraft:table_bonus".equals(obj.get("type").getAsString())
                    && obj.has("chances")) {
                for (JsonElement c : obj.getAsJsonArray("chances")) {
                    out.add(c.getAsString());
                }
            }
            for (var e : obj.entrySet()) {
                collectTableBonusChances(e.getValue(), out);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectTableBonusChances(child, out);
            }
        }
    }
}
