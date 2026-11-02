package org.osmium;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;

/**
 * Runtime loot-table adjustment applied while the loot table registry loads.
 *
 * Apples: vanilla drops them from oak/dark oak leaves at 0.5% per leaf
 * (table_bonus condition, one chance tier per fortune level). The chance
 * lives only in loot table JSON, so the multiplier is applied to the JSON
 * during registry load (RegistryLoadTask.loadFromResource) — config-driven,
 * no data pack needed, survives /reload and datapack reloads.
 */
public final class OsmiumLootAdjust {

    private OsmiumLootAdjust() {}

    /** Called from the NMS registry loader for every loaded data element. */
    public static JsonElement transform(ResourceKey<?> key, JsonElement json) {
        try {
            if (key == null || !Registries.LOOT_TABLE.equals(key.registry())) return json;
            double multiplier = OsmiumConfig.applesDropMultiplier;
            if (multiplier <= 1.0 || json == null || !json.isJsonObject()) return json;
            String path = key.identifier().getPath();
            if (!"blocks/oak_leaves".equals(path) && !"blocks/dark_oak_leaves".equals(path)) return json;

            JsonElement pools = json.getAsJsonObject().get("pools");
            if (pools == null || !pools.isJsonArray()) return json;
            for (JsonElement pool : pools.getAsJsonArray()) {
                if (!pool.isJsonObject()) continue;
                // Only touch pools that actually drop apples — the other
                // pools in the leaves tables (sapling, stick) keep vanilla chances.
                if (!subtreeContainsApple(pool)) continue;
                scaleTableBonusChances(pool, multiplier);
            }
        } catch (Exception ignored) {
            // Fail OPEN: any parse surprise leaves the table vanilla.
        }
        return json;
    }

    private static boolean subtreeContainsApple(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonElement type = obj.get("type");
            if (type != null && type.isJsonPrimitive()
                    && "minecraft:item".equals(type.getAsString())
                    && "minecraft:apple".equals(prim(obj.get("name")))) {
                return true;
            }
            for (var entry : obj.entrySet()) {
                if (subtreeContainsApple(entry.getValue())) return true;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (subtreeContainsApple(child)) return true;
            }
        }
        return false;
    }

    private static String prim(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static void scaleTableBonusChances(JsonElement element, double multiplier) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            JsonElement type = obj.get("type");
            if (type != null && type.isJsonPrimitive()
                    && "minecraft:table_bonus".equals(type.getAsString())) {
                JsonElement chances = obj.get("chances");
                if (chances != null && chances.isJsonArray()) {
                    JsonArray arr = chances.getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonElement c = arr.get(i);
                        if (c != null && c.isJsonPrimitive() && c.getAsJsonPrimitive().isNumber()) {
                            double scaled = Math.min(1.0, c.getAsDouble() * multiplier);
                            arr.set(i, round(scaled));
                        }
                    }
                }
            }
            for (var entry : obj.entrySet()) {
                scaleTableBonusChances(entry.getValue(), multiplier);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                scaleTableBonusChances(child, multiplier);
            }
        }
    }

    private static JsonElement round(double value) {
        // 8 significant decimals keeps the table tidy without changing odds
        return com.google.gson.JsonParser.parseString(String.format(java.util.Locale.ROOT, "%.8f", value));
    }
}
