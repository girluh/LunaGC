package emu.grasscutter.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * item id → weapon_skin_id lookup.
 *
 * <p>The mapping lives in MaterialExcelConfigData.json itself: rows carrying
 * {@code itemUse[].useOp == "ITEM_USE_ADD_WEAPON_SKIN"} put the comma separated skin ids in
 * {@code useParam[0]}. Examples:
 *
 * <pre>
 *   341001 -> 310001,320001,330001,340001,350001   (one id per weapon type)
 *   341801 -> 380001
 *   341802 -> 380002
 *   341803 -> 380003
 *   341901 -> 390001
 * </pre>
 *
 * <p>Note that {@code MaterialType} has no {@code MATERIAL_WEAPON_SKIN} constant, so Gson maps
 * that enum name to null and {@code ItemData} rewrites it to {@code MATERIAL_NONE} - materialType
 * cannot be used for runtime branching, hence scanning {@code itemUse} directly.
 *
 * <p>Optional override file data/WeaponSkins.json, value may be a single id or a list:
 *
 * <pre>{@code
 * { "341801": 380001, "341001": [310001, 320001, 330001, 340001, 350001] }
 * }</pre>
 */
public final class WeaponSkinData {

    private static final Map<Integer, List<Integer>> ITEM_TO_SKINS = new HashMap<>();
    private static final Set<Integer> SKIN_ITEMS = new LinkedHashSet<>();

    static {
        try {
            List<JsonObject> rows =
                    JsonUtils.loadToList(
                            FileUtils.getResourcePath(
                                    "ExcelBinOutput/MaterialExcelConfigData.json"),
                            JsonObject.class);
            for (JsonObject row : rows) {
                if (!row.has("id")) continue;
                if (row.has("materialType")
                        && "MATERIAL_WEAPON_SKIN".equals(row.get("materialType").getAsString())) {
                    SKIN_ITEMS.add(row.get("id").getAsInt());
                }

                var use = row.get("itemUse");
                if (use == null || !use.isJsonArray()) continue;
                var skins = readAddWeaponSkinUse(use.getAsJsonArray());
                if (!skins.isEmpty()) {
                    ITEM_TO_SKINS.put(row.get("id").getAsInt(), skins);
                }
            }
        } catch (Throwable t) {
            System.err.println("Failed to read MATERIAL_WEAPON_SKIN items: " + t);
        }

        try {
            Path path = Path.of("data", "WeaponSkins.json");
            if (Files.exists(path)) {
                var parsed = JsonUtils.decode(Files.readString(path), JsonObject.class);
                if (parsed != null) {
                    parsed.entrySet().forEach(e -> {
                        var ids = toIntList(e.getValue());
                        if (!ids.isEmpty()) {
                            ITEM_TO_SKINS.put(Integer.parseInt(e.getKey()), ids);
                        }
                    });
                }
            }
        } catch (Throwable t) {
            System.err.println("Failed to load data/WeaponSkins.json: " + t);
        }

        emu.grasscutter.Grasscutter.getLogger()
                .info(
                        "WeaponSkinData: {} skin materials, {} with an ADD_WEAPON_SKIN mapping, overrides={}",
                        SKIN_ITEMS.size(),
                        ITEM_TO_SKINS.size(),
                        ITEM_TO_SKINS.keySet().stream().sorted().toList());
    }

    private WeaponSkinData() {}

    private static List<Integer> readAddWeaponSkinUse(JsonArray uses) {
        for (JsonElement el : uses) {
            if (!el.isJsonObject()) continue;
            var use = el.getAsJsonObject();
            var op = use.get("useOp");
            if (op == null || !"ITEM_USE_ADD_WEAPON_SKIN".equals(op.getAsString())) continue;
            var params = use.get("useParam");
            if (params == null || !params.isJsonArray() || params.getAsJsonArray().size() == 0) {
                continue;
            }
            var first = params.getAsJsonArray().get(0);
            if (first == null || !first.isJsonPrimitive()) continue;
            var ids = toIntList(first);
            if (!ids.isEmpty()) return ids;
        }
        return List.of();
    }

    private static List<Integer> toIntList(JsonElement value) {
        if (value == null || value.isJsonNull()) return List.of();
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return List.of(value.getAsInt());
        }
        String raw;
        if (value.isJsonPrimitive()) raw = value.getAsString();
        else if (value.isJsonArray()) {
            var arr = value.getAsJsonArray();
            var out = new ArrayList<Integer>(arr.size());
            for (JsonElement e : arr) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) out.add(e.getAsInt());
                else if (e.isJsonPrimitive()) {
                    for (String part : e.getAsString().split(",")) {
                        part = part.trim();
                        if (!part.isEmpty()) out.add(Integer.parseInt(part));
                    }
                }
            }
            return out;
        } else return List.of();

        var out = new ArrayList<Integer>();
        for (String part : raw.split(",")) {
            part = part.trim();
            if (!part.isEmpty()) out.add(Integer.parseInt(part));
        }
        return out;
    }

    /** Whether this material unlocks one or more weapon skins. */
    public static boolean isWeaponSkinItem(int itemId) {
        return !skinIdsForItem(itemId).isEmpty();
    }

    /** The weapon skin ids unlocked by this material. Empty when the item unlocks nothing. */
    public static List<Integer> skinIdsForItem(int itemId) {
        var ids = ITEM_TO_SKINS.get(itemId);
        return ids == null ? List.of() : Collections.unmodifiableList(ids);
    }

    /** Every skin id this server knows about. */
    public static Set<Integer> allSkinIds() {
        var out = new LinkedHashSet<Integer>();
        ITEM_TO_SKINS.values().forEach(out::addAll);
        return out;
    }
}
