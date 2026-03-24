package org.osmium;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Random Teleport GUI — /rtp opens a 3-row chest inventory with dimension
 * selection (Overworld, Nether, The End). Teleports within world border,
 * charges economy cost via Vault, and applies a configurable countdown.
 */
public class OsmiumRtp {

    private static final Logger LOGGER = Logger.getLogger("Osmium-RTP");

    // Slot layout for a 3-row (27-slot) inventory
    private static final int SLOT_OVERWORLD = 11; // center-left
    private static final int SLOT_NETHER    = 13; // center
    private static final int SLOT_END       = 15; // center-right

    // Confirm GUI slots
    private static final int SLOT_CONFIRM = 11; // green wool — confirm
    private static final int SLOT_CANCEL  = 15; // red wool — cancel

    // Track which players have the RTP GUI open
    private static final Set<UUID> GUI_OPEN = ConcurrentHashMap.newKeySet();

    // Track which players have the confirm GUI open + their chosen dimension
    private static final Map<UUID, ResourceKey<Level>> CONFIRM_OPEN = new ConcurrentHashMap<>();

    // Pending teleports: player UUID -> PendingRtp
    private static final Map<UUID, PendingRtp> PENDING = new ConcurrentHashMap<>();

    // Economy reflection cache
    private static Object economy = null;
    private static java.lang.reflect.Method withdrawMethod = null;
    private static java.lang.reflect.Method balanceMethod = null;
    private static boolean economyChecked = false;

    private record PendingRtp(UUID playerUuid, ResourceKey<Level> dimension, int teleportAtTick, BlockPos target) {}

    private static void debug(String msg) {
        if (OsmiumConfig.rtpDebug) {
            LOGGER.info("[DEBUG] " + msg);
        }
    }

    // ------------------------------------------------------------------
    // Command registration
    // ------------------------------------------------------------------

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        debug("Registering /rtp command (op-only=" + OsmiumConfig.rtpOpOnly + ")");

        dispatcher.register(
                Commands.literal("rtp")
                        .requires(OsmiumConfig.rtpOpOnly
                                ? Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)
                                : Commands.hasPermission(Commands.LEVEL_ALL))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            debug(player.getGameProfile().name() + " executed /rtp command");
                            openGui(player);
                            return 1;
                        })
        );

        // Register Bukkit permission so non-ops can use the command
        // Paper overrides brigadier requirements with Bukkit permission checks
        try {
            org.bukkit.permissions.Permission perm = new org.bukkit.permissions.Permission(
                    "minecraft.command.rtp",
                    "Allows use of the /rtp command",
                    OsmiumConfig.rtpOpOnly
                            ? org.bukkit.permissions.PermissionDefault.OP
                            : org.bukkit.permissions.PermissionDefault.TRUE
            );
            org.bukkit.Bukkit.getPluginManager().addPermission(perm);
            debug("Registered Bukkit permission minecraft.command.rtp (default=" +
                    (OsmiumConfig.rtpOpOnly ? "OP" : "TRUE") + ")");
        } catch (Exception e) {
            debug("Could not register Bukkit permission: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------

    /**
     * Opens the RTP GUI for a player.
     */
    public static void openGui(ServerPlayer player) {
        debug(player.getGameProfile().name() + " opening RTP dimension picker GUI");
        GUI_OPEN.add(player.getUUID());

        SimpleContainer container = new SimpleContainer(27);

        // Overworld — Grass Block
        ItemStack overworld = new ItemStack(Items.GRASS_BLOCK);
        overworld.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7a\u00a7lOverworld"));
        container.setItem(SLOT_OVERWORLD, overworld);

        // Nether — Netherrack
        ItemStack nether = new ItemStack(Items.NETHERRACK);
        nether.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7c\u00a7lThe Nether"));
        container.setItem(SLOT_NETHER, nether);

        // End — End Stone
        ItemStack end = new ItemStack(Items.END_STONE);
        end.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("\u00a75\u00a7lThe End"));
        container.setItem(SLOT_END, end);

        // Fill empty slots with gray stained glass panes
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(" "));
        for (int i = 0; i < 27; i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, filler.copy());
            }
        }

        int containerId = player.nextContainerCounter();
        ChestMenu menu = ChestMenu.threeRows(containerId, player.getInventory(), container);
        menu.setTitle(Component.literal("\u00a78\u00a7lRandom Teleport"));

        player.connection.send(new ClientboundOpenScreenPacket(
                containerId, MenuType.GENERIC_9x3,
                Component.literal("\u00a78\u00a7lRandom Teleport")));
        player.containerMenu = menu;
        player.initMenu(menu);
        debug(player.getGameProfile().name() + " GUI opened successfully (containerId=" + containerId + ")");
    }

    /**
     * Called when a player clicks a slot in any container.
     * Returns true if the click was consumed (was in an RTP GUI).
     */
    public static boolean handleClick(ServerPlayer player, int slotNum) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();

        // --- Confirmation GUI ---
        if (CONFIRM_OPEN.containsKey(uuid)) {
            ResourceKey<Level> dimension = CONFIRM_OPEN.get(uuid);
            debug(name + " clicked slot " + slotNum + " in confirm GUI (dimension=" + getDimensionName(dimension) + ")");

            if (slotNum == SLOT_CONFIRM) {
                debug(name + " confirmed RTP to " + getDimensionName(dimension));
                CONFIRM_OPEN.remove(uuid);
                player.closeContainer();
                executeRtp(player, dimension);
            } else if (slotNum == SLOT_CANCEL) {
                debug(name + " cancelled RTP, returning to dimension picker");
                CONFIRM_OPEN.remove(uuid);
                player.closeContainer();
                openGui(player);
            }
            // Any other slot: consume click, do nothing
            return true;
        }

        // --- Dimension picker GUI ---
        if (!GUI_OPEN.contains(uuid)) return false;

        debug(name + " clicked slot " + slotNum + " in dimension picker GUI");

        ResourceKey<Level> dimension;
        switch (slotNum) {
            case SLOT_OVERWORLD -> dimension = Level.OVERWORLD;
            case SLOT_NETHER    -> dimension = Level.NETHER;
            case SLOT_END       -> dimension = Level.END;
            default -> {
                debug(name + " clicked non-dimension slot " + slotNum + ", ignoring");
                return true; // still in the GUI, consume the click but do nothing
            }
        }

        debug(name + " selected " + getDimensionName(dimension) + ", opening confirm GUI");
        GUI_OPEN.remove(uuid);
        player.closeContainer();
        openConfirmGui(player, dimension);
        return true;
    }

    /**
     * Opens a confirmation GUI showing cost and the selected dimension.
     */
    private static void openConfirmGui(ServerPlayer player, ResourceKey<Level> dimension) {
        debug(player.getGameProfile().name() + " opening confirm GUI for " + getDimensionName(dimension));
        CONFIRM_OPEN.put(player.getUUID(), dimension);

        SimpleContainer container = new SimpleContainer(27);

        double cost = OsmiumConfig.rtpCost;
        String dimName = getDimensionName(dimension);

        // Confirm — green wool
        ItemStack confirm = new ItemStack(Items.LIME_WOOL);
        String confirmName = "\u00a7a\u00a7lConfirm";
        if (cost > 0) {
            confirmName = "\u00a7a\u00a7lConfirm \u00a77(\u00a7e$" + String.format("%.2f", cost) + "\u00a77)";
        }
        confirm.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(confirmName));
        container.setItem(SLOT_CONFIRM, confirm);

        // Info item in center — shows the dimension
        ItemStack info;
        if (dimension == Level.OVERWORLD) info = new ItemStack(Items.GRASS_BLOCK);
        else if (dimension == Level.NETHER) info = new ItemStack(Items.NETHERRACK);
        else info = new ItemStack(Items.END_STONE);
        info.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7f\u00a7lRTP to " + dimName));
        container.setItem(13, info);

        // Cancel — red wool
        ItemStack cancel = new ItemStack(Items.RED_WOOL);
        cancel.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7c\u00a7lCancel"));
        container.setItem(SLOT_CANCEL, cancel);

        // Fill empty slots
        ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        filler.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                Component.literal(" "));
        for (int i = 0; i < 27; i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, filler.copy());
            }
        }

        int containerId = player.nextContainerCounter();
        ChestMenu menu = ChestMenu.threeRows(containerId, player.getInventory(), container);
        menu.setTitle(Component.literal("\u00a78\u00a7lConfirm RTP"));

        player.connection.send(new ClientboundOpenScreenPacket(
                containerId, MenuType.GENERIC_9x3,
                Component.literal("\u00a78\u00a7lConfirm RTP")));
        player.containerMenu = menu;
        player.initMenu(menu);
        debug(player.getGameProfile().name() + " confirm GUI opened (containerId=" + containerId + ", cost=" + cost + ")");
    }

    /**
     * Executes the actual RTP after confirmation — economy check, location finding, countdown.
     */
    private static void executeRtp(ServerPlayer player, ResourceKey<Level> dimension) {
        String name = player.getGameProfile().name();
        debug(name + " executeRtp called (dimension=" + getDimensionName(dimension) + ")");

        if (PENDING.containsKey(player.getUUID())) {
            debug(name + " already has a pending teleport, aborting");
            player.sendSystemMessage(Component.literal("\u00a7cYou already have a teleport pending!"));
            return;
        }

        double cost = OsmiumConfig.rtpCost;
        if (cost > 0) {
            debug(name + " economy check: cost=" + cost);
            initEconomy();
            if (economy == null) {
                debug(name + " economy NOT available (Vault not found or no economy provider)");
                player.sendSystemMessage(Component.literal("\u00a7cEconomy is not available. RTP cost cannot be charged."));
                return;
            }
            double balance = getBalance(player);
            debug(name + " balance=" + balance + " cost=" + cost);
            if (balance < cost) {
                player.sendSystemMessage(Component.literal(
                        "\u00a7cYou need \u00a7e$" + String.format("%.2f", cost)
                                + "\u00a7c to RTP but only have \u00a7e$" + String.format("%.2f", balance) + "\u00a7c."));
                return;
            }
            if (!withdraw(player, cost)) {
                debug(name + " withdraw FAILED");
                player.sendSystemMessage(Component.literal("\u00a7cFailed to charge your account. Try again."));
                return;
            }
            debug(name + " withdraw OK");
            player.sendSystemMessage(Component.literal(
                    "\u00a7aCharged \u00a7e$" + String.format("%.2f", cost) + "\u00a7a for RTP."));
        }

        MinecraftServer server = player.level().getServer();
        ServerLevel targetLevel = server.getLevel(dimension);
        if (targetLevel == null) {
            debug(name + " target dimension " + getDimensionName(dimension) + " is NOT loaded");
            player.sendSystemMessage(Component.literal("\u00a7cThat dimension is not loaded."));
            return;
        }
        debug(name + " target dimension " + getDimensionName(dimension) + " is loaded");

        debug(name + " finding safe location (minDist=" + OsmiumConfig.rtpMinDistance
                + " maxDist=" + OsmiumConfig.rtpMaxDistance + ")");
        BlockPos target = findSafeLocation(targetLevel);
        if (target == null) {
            debug(name + " could NOT find safe location after 50 attempts");
            player.sendSystemMessage(Component.literal("\u00a7cCould not find a safe location. Try again."));
            if (cost > 0) deposit(player, cost);
            return;
        }
        debug(name + " found safe location at " + target.getX() + ", " + target.getY() + ", " + target.getZ());

        int delayTicks = OsmiumConfig.rtpDelaySeconds * 20;
        int teleportAt = server.getTickCount() + delayTicks;

        PENDING.put(player.getUUID(), new PendingRtp(player.getUUID(), dimension, teleportAt, target));
        debug(name + " pending teleport created (teleportAt tick=" + teleportAt
                + " current=" + server.getTickCount() + " delay=" + delayTicks + " ticks)");

        if (OsmiumConfig.rtpDelaySeconds > 0) {
            player.sendSystemMessage(Component.literal(
                    "\u00a7eTeleporting in \u00a7f" + OsmiumConfig.rtpDelaySeconds
                            + "\u00a7e seconds... Don't move!"));
        }
    }

    /**
     * Called when a player closes any container.
     */
    public static void handleClose(ServerPlayer player) {
        boolean wasGui = GUI_OPEN.remove(player.getUUID());
        boolean wasConfirm = CONFIRM_OPEN.remove(player.getUUID()) != null;
        if (wasGui || wasConfirm) {
            debug(player.getGameProfile().name() + " closed RTP GUI (wasPickerOpen=" + wasGui + " wasConfirmOpen=" + wasConfirm + ")");
        }
    }

    // ------------------------------------------------------------------
    // Tick — process pending teleports
    // ------------------------------------------------------------------

    /**
     * Called every tick from MinecraftServer.tickChildren().
     */
    public static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;

        int currentTick = server.getTickCount();
        Iterator<Map.Entry<UUID, PendingRtp>> it = PENDING.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, PendingRtp> entry = it.next();
            PendingRtp pending = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerUuid);

            // Player disconnected
            if (player == null) {
                debug("Player " + pending.playerUuid + " disconnected, removing pending RTP");
                it.remove();
                continue;
            }

            // Not time yet — send countdown messages at whole seconds
            if (currentTick < pending.teleportAtTick) {
                int ticksLeft = pending.teleportAtTick - currentTick;
                if (ticksLeft % 20 == 0) {
                    int secondsLeft = ticksLeft / 20;
                    if (secondsLeft > 0 && secondsLeft <= 5) {
                        player.sendSystemMessage(Component.literal(
                                "\u00a7eTeleporting in \u00a7f" + secondsLeft + "\u00a7e..."));
                    }
                }
                continue;
            }

            // Time to teleport
            it.remove();
            String name = player.getGameProfile().name();

            ServerLevel targetLevel = server.getLevel(pending.dimension);
            if (targetLevel == null) {
                debug(name + " teleport FAILED — dimension no longer available");
                player.sendSystemMessage(Component.literal("\u00a7cDimension no longer available."));
                continue;
            }

            BlockPos target = pending.target;
            debug(name + " teleporting NOW to " + getDimensionName(pending.dimension)
                    + " at " + target.getX() + ", " + target.getY() + ", " + target.getZ());

            player.teleportTo(targetLevel,
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5,
                    Set.of(), player.getYRot(), player.getXRot(), true,
                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND);

            String dimName = getDimensionName(pending.dimension);
            player.sendSystemMessage(Component.literal(
                    "\u00a7aTeleported to \u00a7f" + dimName + "\u00a7a at \u00a7f"
                            + target.getX() + ", " + target.getY() + ", " + target.getZ()));
            debug(name + " teleport complete");
        }
    }

    /**
     * Cancel a pending RTP (e.g. on disconnect).
     */
    public static void cancel(UUID playerUuid) {
        boolean hadPending = PENDING.remove(playerUuid) != null;
        boolean hadGui = GUI_OPEN.remove(playerUuid);
        boolean hadConfirm = CONFIRM_OPEN.remove(playerUuid) != null;
        if (hadPending || hadGui || hadConfirm) {
            debug("Cancelled RTP state for " + playerUuid + " (pending=" + hadPending
                    + " gui=" + hadGui + " confirm=" + hadConfirm + ")");
        }
    }

    // ------------------------------------------------------------------
    // Location finding
    // ------------------------------------------------------------------

    private static BlockPos findSafeLocation(ServerLevel level) {
        WorldBorder border = level.getWorldBorder();
        double centerX = border.getCenterX();
        double centerZ = border.getCenterZ();
        double borderRadius = border.getSize() / 2.0;

        int minDist = OsmiumConfig.rtpMinDistance;
        int maxDist = OsmiumConfig.rtpMaxDistance;

        // Clamp max distance to world border
        if (maxDist > borderRadius - 1) {
            maxDist = (int) (borderRadius - 1);
            debug("Clamped maxDist to " + maxDist + " (border radius=" + borderRadius + ")");
        }
        if (minDist > maxDist) {
            minDist = maxDist / 2;
            debug("Adjusted minDist to " + minDist + " (was > maxDist)");
        }
        if (maxDist <= 0) {
            debug("maxDist <= 0 after clamping, no valid area to teleport");
            return null;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();

        // Try up to 50 times to find a safe spot
        for (int attempt = 0; attempt < 50; attempt++) {
            // Random distance and angle for uniform distribution
            double distance = minDist + random.nextDouble() * (maxDist - minDist);
            double angle = random.nextDouble() * 2 * Math.PI;

            int x = (int) (centerX + distance * Math.cos(angle));
            int z = (int) (centerZ + distance * Math.sin(angle));

            // Verify within world border
            if (!border.isWithinBounds(new BlockPos(x, 64, z))) {
                debug("Attempt " + attempt + ": (" + x + ", " + z + ") outside world border");
                continue;
            }

            // Get the highest block
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);

            // Nether: scan downward from y=120 for air pocket
            if (level.dimension() == Level.NETHER) {
                y = findNetherSafe(level, x, z);
                if (y < 0) {
                    debug("Attempt " + attempt + ": (" + x + ", " + z + ") no safe nether pocket");
                    continue;
                }
            }

            // Basic safety: block below must be solid, block at feet and head must be passable
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos below = feet.below();
            BlockPos head = feet.above();

            if (!level.getBlockState(below).isSolid()) {
                debug("Attempt " + attempt + ": (" + x + ", " + y + ", " + z + ") block below not solid");
                continue;
            }
            if (level.getBlockState(feet).isSolid()) {
                debug("Attempt " + attempt + ": (" + x + ", " + y + ", " + z + ") feet block is solid");
                continue;
            }
            if (level.getBlockState(head).isSolid()) {
                debug("Attempt " + attempt + ": (" + x + ", " + y + ", " + z + ") head block is solid");
                continue;
            }

            // Don't spawn in lava or water
            if (level.getBlockState(feet).liquid()) {
                debug("Attempt " + attempt + ": (" + x + ", " + y + ", " + z + ") feet in liquid");
                continue;
            }
            if (level.getBlockState(below).liquid()) {
                debug("Attempt " + attempt + ": (" + x + ", " + y + ", " + z + ") standing on liquid");
                continue;
            }

            debug("Attempt " + attempt + ": found safe location at (" + x + ", " + y + ", " + z + ")");
            return feet;
        }

        return null; // couldn't find safe spot
    }

    private static int findNetherSafe(ServerLevel level, int x, int z) {
        // Scan from y=120 downward for a 2-high air pocket above a solid block
        for (int y = 120; y > 4; y--) {
            BlockPos below = new BlockPos(x, y - 1, z);
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos head = new BlockPos(x, y + 1, z);

            if (level.getBlockState(below).isSolid()
                    && !level.getBlockState(feet).isSolid()
                    && !level.getBlockState(head).isSolid()
                    && !level.getBlockState(feet).liquid()
                    && !level.getBlockState(below).liquid()) {
                return y;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Economy (Vault reflection)
    // ------------------------------------------------------------------

    private static void initEconomy() {
        if (economy != null || economyChecked) return;
        debug("Initializing Vault economy...");
        try {
            org.bukkit.plugin.Plugin vaultPlugin = org.bukkit.Bukkit.getPluginManager().getPlugin("Vault");
            if (vaultPlugin == null) {
                debug("Vault plugin NOT found");
                economyChecked = true;
                return;
            }
            debug("Vault plugin found, looking for economy provider...");
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy",
                    true, vaultPlugin.getClass().getClassLoader());
            org.bukkit.plugin.RegisteredServiceProvider<?> rsp =
                    org.bukkit.Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp != null) {
                economy = rsp.getProvider();
                balanceMethod = economy.getClass().getMethod("getBalance", org.bukkit.OfflinePlayer.class);
                withdrawMethod = economy.getClass().getMethod("withdrawPlayer", org.bukkit.OfflinePlayer.class, double.class);
                debug("Economy provider found: " + economy.getClass().getName());
            } else {
                debug("No economy provider registered with Vault");
                economyChecked = true;
            }
        } catch (ClassNotFoundException e) {
            debug("Vault economy class not found: " + e.getMessage());
            economyChecked = true;
        } catch (Exception e) {
            debug("Economy init error: " + e.getMessage());
        }
    }

    private static double getBalance(ServerPlayer player) {
        if (economy == null || balanceMethod == null) return 0.0;
        try {
            Object result = balanceMethod.invoke(economy, (org.bukkit.OfflinePlayer) player.getBukkitEntity());
            return ((Number) result).doubleValue();
        } catch (Exception e) {
            debug("getBalance error: " + e.getMessage());
        }
        return 0.0;
    }

    private static boolean withdraw(ServerPlayer player, double amount) {
        if (economy == null || withdrawMethod == null) return false;
        try {
            Object result = withdrawMethod.invoke(economy, (org.bukkit.OfflinePlayer) player.getBukkitEntity(), amount);
            // EconomyResponse has a transactionSuccess() method
            java.lang.reflect.Method successMethod = result.getClass().getMethod("transactionSuccess");
            return (boolean) successMethod.invoke(result);
        } catch (Exception e) {
            debug("withdraw error: " + e.getMessage());
        }
        return false;
    }

    private static void deposit(ServerPlayer player, double amount) {
        if (economy == null) return;
        try {
            java.lang.reflect.Method depositMethod = economy.getClass().getMethod("depositPlayer",
                    org.bukkit.OfflinePlayer.class, double.class);
            depositMethod.invoke(economy, (org.bukkit.OfflinePlayer) player.getBukkitEntity(), amount);
            debug(player.getGameProfile().name() + " refunded $" + amount);
        } catch (Exception e) {
            debug("deposit error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Util
    // ------------------------------------------------------------------

    private static String getDimensionName(ResourceKey<Level> dim) {
        if (dim == Level.OVERWORLD) return "Overworld";
        if (dim == Level.NETHER) return "The Nether";
        if (dim == Level.END) return "The End";
        return dim.identifier().toString();
    }
}
