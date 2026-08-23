package org.osmium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.mojang.serialization.Codec;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * Custom recipe system.
 *
 * Two ways to create recipes:
 *  1. /customrecipes (op) — chest-GUI builder: place ingredients in the 3x3,
 *     the result item in the result slot, hit save. Togglable shapeless mode.
 *  2. Drop vanilla-format recipe JSONs into osmium-recipes/*.json — loaded
 *     at startup / on reload. The GUI writes exactly this format too.
 *
 * Recipes register live (no restart). Reload removes previously-loaded
 * custom recipes first so edits/deletions apply cleanly.
 */
public final class OsmiumRecipes {

    private OsmiumRecipes() {}

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Osmium-Recipes");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static File recipesDir;
    private static final Set<ResourceKey<Recipe<?>>> LOADED_CUSTOM = new HashSet<>();

    // ------------------------------------------------------------------
    // Config-ish
    // ------------------------------------------------------------------

    public static boolean enabled() {
        return OsmiumConfig.recipesEnabled;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public static void loadAll(MinecraftServer server) {
        if (!enabled()) return;
        recipesDir = new File("osmium-recipes");
        if (!recipesDir.exists()) recipesDir.mkdirs();

        // Remove previously loaded customs so deletions/edits apply
        var manager = server.getRecipeManager();
        for (ResourceKey<Recipe<?>> key : LOADED_CUSTOM) {
            manager.removeRecipe(key);
        }
        LOADED_CUSTOM.clear();

        File[] files = recipesDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
        int added = 0;
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                try {
                    JsonObject json = GSON.fromJson(java.nio.file.Files.readString(f.toPath()), JsonObject.class);
                    if (json == null) continue;
                    String fileName = f.getName().substring(0, f.getName().length() - 5)
                            .toLowerCase(Locale.ROOT).replace(' ', '_');
                    ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE,
                            Identifier.fromNamespaceAndPath("osmium", fileName));
                    var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                    Recipe<?> recipe = Recipe.CODEC.parse(ops, json)
                            .getOrThrow(s -> new RuntimeException(s));
                    manager.addRecipe(new RecipeHolder<>(key, recipe));
                    LOADED_CUSTOM.add(key);
                    added++;
                } catch (Exception ex) {
                    LOGGER.error("Failed to load recipe {}: {}", f.getName(), ex.getMessage());
                }
            }
        }
        LOGGER.info("Loaded {} custom recipes from osmium-recipes/", added);
    }

    /** Persists a recipe holder as vanilla-format JSON and remembers it. */
    private static void persist(MinecraftServer server, RecipeHolder<?> holder) throws Exception {
        var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        com.mojang.serialization.DataResult<JsonElement> dr = ((com.mojang.serialization.Codec<RecipeHolder<?>>) (Codec) Recipe.CODEC).encodeStart(ops, holder);
        JsonElement json = dr.getOrThrow(s -> new RuntimeException(s));
        String pretty = GSON.toJson(json);
        String fileName = holder.id().identifier().getPath() + ".json";
        java.nio.file.Files.writeString(new File(recipesDir, fileName).toPath(), pretty);
    }

    // ------------------------------------------------------------------
    // Command registration
    // ------------------------------------------------------------------

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("customrecipes")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!enabled()) return 0;
                            openBuilder(player);
                            return 1;
                        }));

        dispatcher.register(
                Commands.literal("osmium-recipes")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("reload").executes(ctx -> {
                            loadAll(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Custom recipes reloaded (" + LOADED_CUSTOM.size() + ")"), true);
                            return 1;
                        }))
                        .then(Commands.literal("list").executes(ctx -> {
                            StringBuilder sb = new StringBuilder("Custom recipes:\n");
                            for (var key : LOADED_CUSTOM) sb.append("• ").append(key.identifier()).append('\n');
                            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                            return 1;
                        })));
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------

    private static final int[] GRID_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int RESULT_SLOT = 24;
    private static final int SLOT_TOGGLE = 48;
    private static final int SLOT_SAVE = 49;
    private static final int SLOT_CANCEL = 53;

    private static final Set<UUID> BUILDER_OPEN = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, SimpleContainer> BUILDERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> SHAPELESS = new ConcurrentHashMap<>();

    public static void openBuilder(ServerPlayer player) {
        UUID uuid = player.getUUID();
        clearState(uuid);
        BUILDER_OPEN.add(uuid);

        MinecraftServer server = player.level().getServer();
        SimpleContainer container = new SimpleContainer(54);

        // Frame
        ItemStack frame = new ItemStack(net.minecraft.world.item.Items.STAINED_GLASS_PANE.gray());
        frame.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(" "));
        int[] frameSlots = {0,1,2,3,4,5,6,7,8, 9,13,15,16,17, 18,22,25,26, 27,31,32,33,34, 36,37,38,39,40,41,42,43,44, 46,47,50,51,52};
        for (int s : frameSlots) container.setItem(s, frame.copy());

        // Grid label markers (invisible barrier above/below handled by empties)
        ItemStack gridHint = new ItemStack(net.minecraft.world.item.Items.STAINED_GLASS_PANE.lightBlue());
        gridHint.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7b\u00a7lIngredients (3x3)"));
        container.setItem(9, gridHint.copy());

        // Result slot hint
        ItemStack arrow = new ItemStack(net.minecraft.world.item.Items.ARROW);
        arrow.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("\u00a77Place \u00a7fresult item \u00a77here \u2192"));
        container.setItem(23, arrow);

        // Shapeless toggle
        boolean shapeless = SHAPELESS.getOrDefault(uuid, false);
        updateToggleButton(container, shapeless);

        // Save
        ItemStack save = new ItemStack(net.minecraft.world.item.Items.WOOL.lime());
        save.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7a\u00a7lSAVE RECIPE"));
        container.setItem(SLOT_SAVE, save);

        // Cancel
        ItemStack cancel = new ItemStack(net.minecraft.world.item.Items.WOOL.red());
        cancel.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7c\u00a7lCancel"));
        container.setItem(SLOT_CANCEL, cancel);

        // Info book
        ItemStack book = new ItemStack(net.minecraft.world.item.Items.BOOK);
        book.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7f\u00a7lHow to use"));
        book.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                        Component.literal("\u00a771. Place ingredients in the 3x3"),
                        Component.literal("\u00a772. Place the crafted result on the right"),
                        Component.literal("\u00a773. Toggle shapeless if order shouldn't matter"),
                        Component.literal("\u00a774. Save — recipe works instantly & persists"))));
        container.setItem(45, book);

        int containerId = player.nextContainerCounter();
        ChestMenu menu = ChestMenu.threeRows(containerId, player.getInventory(), container);
        // 6-row variant: use sixRows if available
        menu.setTitle(Component.literal("\u00a78\u00a7lCustom Recipe Builder"));

        player.connection.send(new net.minecraft.network.protocol.game.ClientboundOpenScreenPacket(
                containerId, net.minecraft.world.inventory.MenuType.GENERIC_9x6,
                Component.literal("\u00a78\u00a7lCustom Recipe Builder")));
        player.containerMenu = menu;
        player.initMenu(menu);

        BUILDERS.put(uuid, container);
    }

    private static void updateToggleButton(SimpleContainer container, boolean shapeless) {
        ItemStack t = new ItemStack(shapeless ? net.minecraft.world.item.Items.WOOL.orange()
                                              : net.minecraft.world.item.Items.WOOL.lime());
        t.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(shapeless ? "\u00a76\u00a7lShapeless: ON" : "\u00a7a\u00a7lShaped: ON"));
        t.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                        Component.literal("\u00a77Click to switch mode"))));
        container.setItem(SLOT_TOGGLE, t);
    }

    /** Returns true when the click was inside the recipe builder GUI. */
    public static boolean handleClick(ServerPlayer player, int slot) {
        UUID uuid = player.getUUID();
        if (!BUILDER_OPEN.contains(uuid)) return false;

        SimpleContainer container = BUILDERS.get(uuid);
        if (container == null) return true;

        if (slot == SLOT_TOGGLE) {
            boolean now = !SHAPELESS.getOrDefault(uuid, false);
            SHAPELESS.put(uuid, now);
            updateToggleButton(container, now);
            player.containerMenu.sendAllDataToRemote();
            return true;
        }
        if (slot == SLOT_SAVE) {
            player.closeContainer();
            buildAndRegister(player, container);
            return true;
        }
        if (slot == SLOT_CANCEL) {
            clearState(uuid);
            player.closeContainer();
            return true;
        }

        // Grid + result slots are editable by design; everything else consumed
        boolean editable = slot == RESULT_SLOT;
        for (int s : GRID_SLOTS) if (s == slot) editable = true;
        return !editable || true; // all clicks consumed while builder open
    }

    public static void handleClose(ServerPlayer player) {
        clearState(player.getUUID());
    }

    private static void clearState(UUID uuid) {
        BUILDER_OPEN.remove(uuid);
        BUILDERS.remove(uuid);
        SHAPELESS.remove(uuid);
    }

    // ------------------------------------------------------------------
    // Build + register from GUI contents
    // ------------------------------------------------------------------

    private static void buildAndRegister(ServerPlayer player, SimpleContainer container) {
        MinecraftServer server = player.level().getServer();

        ItemStack result = container.getItem(RESULT_SLOT);
        if (result.isEmpty()) {
            player.sendSystemMessage(Component.literal("\u00a7cPut the crafted result in the right slot."));
            return;
        }

        // Collect grid contents
        List<ItemStack> grid = new ArrayList<>();
        for (int s : GRID_SLOTS) {
            ItemStack is = container.getItem(s);
            grid.add(is.isEmpty() ? null : is.copy());
        }

        boolean any = grid.stream().anyMatch(java.util.Objects::nonNull);
        if (!any) {
            player.sendSystemMessage(Component.literal("\u00a7cPlace at least one ingredient."));
            return;
        }

        boolean shapeless = SHAPELESS.getOrDefault(player.getUUID(), false);
        String baseName = BuiltInRegistries.ITEM.getKey(result.getItem()).getPath();
        String idPath = baseName + "_" + Long.toHexString(System.currentTimeMillis() & 0xFFFFFFL);

        Recipe<?> recipe;
        try {
            if (shapeless) {
                List<Ingredient> ingredients = new ArrayList<>();
                for (ItemStack is : grid) {
                    if (is != null && !is.isEmpty()) ingredients.add(Ingredient.of(is.getItem()));
                }
                recipe = new ShapelessRecipe(
                        new Recipe.CommonInfo(true),
                        new net.minecraft.world.item.crafting.CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                        templateOf(result), ingredients);
            } else {
                // bounding box of filled cells
                int minR = 3, maxR = -1, minC = 3, maxC = -1;
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        if (grid.get(r * 3 + c) != null) {
                            minR = Math.min(minR, r); maxR = Math.max(maxR, r);
                            minC = Math.min(minC, c); maxC = Math.max(maxC, c);
                        }
                    }
                }
                int height = maxR - minR + 1;
                int width = maxC - minC + 1;

                Map<Character, Ingredient> key = new LinkedHashMap<>();
                StringBuilder[] rows = new StringBuilder[height];
                for (int i = 0; i < height; i++) rows[i] = new StringBuilder();

                char nextKey = 'a';
                for (int r = 0; r < height; r++) {
                    for (int c = 0; c < width; c++) {
                        ItemStack is = grid.get((r + minR) * 3 + (c + minC));
                        if (is == null) { rows[r].append(' '); continue; }
                        char k = keyFor(key, is.getItem());
                        if (k == 0) { k = nextKey++; key.put(k, Ingredient.of(is.getItem())); }
                        rows[r].append(k);
                    }
                }
                String[] pattern = new String[height];
                for (int i = 0; i < height; i++) pattern[i] = rows[i].toString();

                recipe = new ShapedRecipe(
                        new Recipe.CommonInfo(true),
                        new net.minecraft.world.item.crafting.CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                        ShapedRecipePattern.of(key, pattern),
                        templateOf(result));
            }

            ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE,
                    Identifier.fromNamespaceAndPath("osmium", idPath));
            RecipeHolder<?> holder = new RecipeHolder<>(key, recipe);
            server.getRecipeManager().addRecipe(holder);
            LOADED_CUSTOM.add(key);
            persist(server, holder);

            player.sendSystemMessage(Component.literal(
                    "\u00a7aRecipe registered: \u00a7fosmium:" + idPath
                            + "\u00a7a (" + (shapeless ? "shapeless" : "shaped") + ", persists in osmium-recipes/)"));

        } catch (Exception ex) {
            LOGGER.error("GUI recipe creation failed", ex);
            player.sendSystemMessage(Component.literal("\u00a7cFailed: " + ex.getMessage()));
        }
    }

    private static char keyFor(Map<Character, Ingredient> key, net.minecraft.world.item.Item item) {
        for (var e : key.entrySet()) {
            // same item already mapped?
            if (e.getValue().test(new ItemStack(item))) return e.getKey();
        }
        return 0;
    }

    private static ItemStackTemplate templateOf(ItemStack stack) {
        return new ItemStackTemplate(
                BuiltInRegistries.ITEM.wrapAsHolder(stack.getItem()),
                stack.getCount(),
                DataComponentPatch.EMPTY);
    }
}
