package org.osmium;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class OsmiumTeam {

    private static final Logger LOGGER = Logger.getLogger("Osmium-Team");

    // --- Data model ---

    private static class TeamData {
        UUID teamId;
        UUID leaderUuid;
        String leaderName;
        // When true, teammates can damage each other. Default: protected.
        boolean friendlyFire = false;
        final Set<UUID> memberUuids = new LinkedHashSet<>();
        final Map<UUID, String> memberNames = new ConcurrentHashMap<>();

        TeamData(UUID teamId, UUID leaderUuid, String leaderName) {
            this.teamId = teamId;
            this.leaderUuid = leaderUuid;
            this.leaderName = leaderName;
            this.memberUuids.add(leaderUuid);
            this.memberNames.put(leaderUuid, leaderName);
        }
    }

    private record PendingInvite(UUID inviterUuid, UUID inviteeUuid, UUID teamId, int expiresAtTick) {}

    // --- Persistence format ---

    private static class TeamJson {
        String id;
        String leader;
        boolean friendlyFire = false;
        Map<String, String> members; // uuid -> name
    }

    // --- State ---

    private static final Map<UUID, TeamData> teams = new ConcurrentHashMap<>();           // teamId -> team
    private static final Map<UUID, UUID> playerToTeam = new ConcurrentHashMap<>();        // playerUuid -> teamId
    private static final Map<UUID, PendingInvite> pendingInvites = new ConcurrentHashMap<>(); // inviteeUuid -> invite

    // GUI tracking
    private static final Set<UUID> GUI_MAIN = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> GUI_MEMBERS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> GUI_INVITE_LIST = new ConcurrentHashMap<>();      // player -> page
    private static final Map<UUID, List<UUID>> GUI_INVITE_SLOTS = new ConcurrentHashMap<>();  // player -> ordered UUIDs in slots
    private static final Map<UUID, UUID> GUI_CONFIRM_INVITE = new ConcurrentHashMap<>();      // player -> target
    private static final Map<UUID, UUID> GUI_MANAGE = new ConcurrentHashMap<>();              // player -> target member
    private static final Map<UUID, List<UUID>> GUI_MEMBERS_SLOTS = new ConcurrentHashMap<>(); // player -> ordered member UUIDs

    // Persistence — debounced like the alt tracker: GUI actions mark dirty,
    // one background task writes the file. No main-thread disk I/O.
    private static File dataFile;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean();
    private static final ScheduledExecutorService SAVER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Osmium-Team-Saver");
        t.setDaemon(true);
        return t;
    });

    private static void debug(String msg) {
        if (OsmiumConfig.teamDebug) {
            LOGGER.info("[DEBUG] " + msg);
        }
    }

    private static MinecraftServer server() {
        MinecraftServer srv = MinecraftServer.getServer();
        if (srv == null) throw new IllegalStateException("Server not available");
        return srv;
    }

    /**
     * Sends a message to every online member of the team.
     */
    private static void notifyTeam(MinecraftServer server, TeamData team, Component message) {
        for (UUID memberUuid : team.memberUuids) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberUuid);
            if (member != null) {
                member.sendSystemMessage(message);
            }
        }
    }

    // ------------------------------------------------------------------
    // Init / Persistence
    // ------------------------------------------------------------------

    public static void init(File serverDir) {
        dataFile = new File(serverDir, "osmium-teams.json");
        load();
        debug("Team system initialized (" + teams.size() + " teams loaded)");
    }

    public static void initPermission() {
        if (!OsmiumConfig.teamEnabled) return;
        try {
            org.bukkit.permissions.Permission perm = new org.bukkit.permissions.Permission(
                    "minecraft.command.party",
                    "Allows use of the /party command",
                    org.bukkit.permissions.PermissionDefault.TRUE
            );
            org.bukkit.Bukkit.getPluginManager().addPermission(perm);
            debug("Registered Bukkit permission minecraft.command.team");
        } catch (Exception e) {
            debug("Could not register Bukkit permission: " + e.getMessage());
        }
    }

    private static void load() {
        if (dataFile == null || !dataFile.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<TeamJson>>() {}.getType();
            List<TeamJson> list = GSON.fromJson(reader, listType);
            if (list == null) return;
            teams.clear();
            playerToTeam.clear();
            for (TeamJson tj : list) {
                UUID teamId = UUID.fromString(tj.id);
                UUID leaderId = UUID.fromString(tj.leader);
                String leaderName = tj.members != null ? tj.members.getOrDefault(tj.leader, "Unknown") : "Unknown";
                TeamData team = new TeamData(teamId, leaderId, leaderName);
                team.friendlyFire = tj.friendlyFire;
                if (tj.members != null) {
                    for (Map.Entry<String, String> entry : tj.members.entrySet()) {
                        UUID memberUuid = UUID.fromString(entry.getKey());
                        team.memberUuids.add(memberUuid);
                        team.memberNames.put(memberUuid, entry.getValue());
                        playerToTeam.put(memberUuid, teamId);
                    }
                }
                teams.put(teamId, team);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to load osmium-teams.json: " + e.getMessage());
        }
    }

    private static void save() {
        scheduleFlush();
    }

    /** Writes teams to disk now (shutdown hook / explicit flushes). */
    public static void flush() {
        FLUSH_SCHEDULED.set(false);
        saveNow();
    }

    private static void scheduleFlush() {
        if (FLUSH_SCHEDULED.compareAndSet(false, true)) {
            SAVER.schedule(() -> {
                try { saveNow(); }
                catch (Exception e) { LOGGER.warning("Team flush failed: " + e.getMessage()); }
                finally { FLUSH_SCHEDULED.set(false); }
            }, 3, TimeUnit.SECONDS);
        }
    }

    private static void saveNow() {
        if (dataFile == null) return;
        List<TeamJson> list = new ArrayList<>();
        for (TeamData team : teams.values()) {
            TeamJson tj = new TeamJson();
            tj.id = team.teamId.toString();
            tj.leader = team.leaderUuid.toString();
            tj.friendlyFire = team.friendlyFire;
            tj.members = new LinkedHashMap<>();
            for (UUID memberUuid : team.memberUuids) {
                tj.members.put(memberUuid.toString(), team.memberNames.getOrDefault(memberUuid, "Unknown"));
            }
            list.add(tj);
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(dataFile), StandardCharsets.UTF_8)) {
            GSON.toJson(list, writer);
        } catch (Exception e) {
            LOGGER.warning("Failed to save osmium-teams.json: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Command registration
    // ------------------------------------------------------------------

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Always register — config may not be loaded yet at registration time.
        // Enabled check happens at execution time (same pattern as /rtp).
        dispatcher.register(
                Commands.literal("team")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!OsmiumConfig.teamEnabled) {
                                player.sendSystemMessage(Component.literal("\u00a7cTeams are disabled."));
                                return 0;
                            }
                            debug(player.getGameProfile().name() + " executed /team");
                            openMainGui(player);
                            return 1;
                        })
                        .then(Commands.literal("accept")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    if (!OsmiumConfig.teamEnabled) return 0;
                                    acceptInvite(player);
                                    return 1;
                                }))
                        .then(Commands.literal("deny")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    if (!OsmiumConfig.teamEnabled) return 0;
                                    declineInvite(player);
                                    return 1;
                                }))
        );
    }

    /**
     * Builds a clickable chat button that runs a command when clicked.
     */
    private static Component chatButton(String text, String command, ChatFormatting color) {
        return Component.literal(text)
                .withStyle(style -> style
                        .withColor(color)
                        .withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    // ------------------------------------------------------------------
    // PvP enforcement
    // ------------------------------------------------------------------

    /**
     * Returns true when the attack must be cancelled: victim and attacker are
     * on the same team AND the team leader has friendly fire disabled.
     */
    public static boolean isFriendlyFireBlocked(ServerPlayer victim, ServerPlayer attacker) {
        if (!OsmiumConfig.teamEnabled) return false;
        UUID teamId = playerToTeam.get(victim.getUUID());
        if (teamId == null || !teamId.equals(playerToTeam.get(attacker.getUUID()))) return false;
        TeamData team = teams.get(teamId);
        return team != null && !team.friendlyFire;
    }

    // ------------------------------------------------------------------
    // PvP check
    // ------------------------------------------------------------------

    public static boolean areTeammates(UUID a, UUID b) {
        UUID teamA = playerToTeam.get(a);
        return teamA != null && teamA.equals(playerToTeam.get(b));
    }

    // ------------------------------------------------------------------
    // GUI: Main Menu
    // ------------------------------------------------------------------

    private static void openMainGui(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        debug(name + " opening main team GUI");
        clearGuiState(uuid);
        GUI_MAIN.add(uuid);

        SimpleContainer container = new SimpleContainer(27);
        UUID teamId = playerToTeam.get(uuid);

        if (teamId == null) {
            // No team — show Create Team + Pending Invites
            ItemStack create = new ItemStack(Items.EMERALD);
            create.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7a\u00a7lCreate Team"));
            container.setItem(11, create);

            PendingInvite invite = pendingInvites.get(uuid);
            if (invite != null) {
                ItemStack inviteItem = new ItemStack(Items.GOLDEN_APPLE);
                MinecraftServer server = player.level().getServer();
                ServerPlayer inviter = server.getPlayerList().getPlayer(invite.inviterUuid());
                String inviterName = inviter != null ? inviter.getGameProfile().name() : "Unknown";
                inviteItem.set(DataComponents.CUSTOM_NAME,
                        Component.literal("\u00a7e\u00a7lInvite from \u00a7f" + inviterName));
                inviteItem.set(DataComponents.LORE, new ItemLore(List.of(
                        Component.literal("\u00a77Accept or decline in chat"))));
                container.setItem(15, inviteItem);
            } else {
                ItemStack noInvite = new ItemStack(Items.WRITABLE_BOOK);
                noInvite.set(DataComponents.CUSTOM_NAME,
                        Component.literal("\u00a77No Pending Invites"));
                container.setItem(15, noInvite);
            }
        } else {
            // Has team — show Members, Invite (if leader), Leave/Disband
            TeamData team = teams.get(teamId);

            ItemStack members = new ItemStack(Items.PLAYER_HEAD);
            members.set(DataComponents.CUSTOM_NAME,
                    Component.literal("\u00a7b\u00a7lMembers \u00a77(" + team.memberUuids.size()
                            + "/" + OsmiumConfig.teamMaxSize + ")"));
            container.setItem(11, members);

            if (team.leaderUuid.equals(uuid)) {
                // Friendly fire toggle
                ItemStack ff = new ItemStack(team.friendlyFire ? Items.WOOL.red() : Items.WOOL.lime());
                ff.set(DataComponents.CUSTOM_NAME,
                        Component.literal(team.friendlyFire
                                ? "\u00a7c\u00a7lFriendly Fire: ON"
                                : "\u00a7a\u00a7lFriendly Fire: OFF"));
                ff.set(DataComponents.LORE, new ItemLore(List.of(
                        Component.literal("\u00a77Click to " + (team.friendlyFire ? "disable" : "enable")
                                + " teammate damage"))));
                container.setItem(12, ff);

                ItemStack invite = new ItemStack(Items.EMERALD);
                invite.set(DataComponents.CUSTOM_NAME,
                        Component.literal("\u00a7a\u00a7lInvite Player"));
                container.setItem(13, invite);
            }

            ItemStack leave;
            if (team.leaderUuid.equals(uuid)) {
                leave = new ItemStack(Items.BARRIER);
                leave.set(DataComponents.CUSTOM_NAME,
                        Component.literal("\u00a7c\u00a7lDisband Team"));
            } else {
                leave = new ItemStack(Items.BARRIER);
                leave.set(DataComponents.CUSTOM_NAME,
                        Component.literal("\u00a7c\u00a7lLeave Team"));
            }
            container.setItem(15, leave);
        }

        fillEmpty(container, 27);
        openScreen(player, container, "\u00a78\u00a7lTeam");
    }

    // ------------------------------------------------------------------
    // GUI: Members List
    // ------------------------------------------------------------------

    private static void openMembersGui(ServerPlayer player) {
        UUID uuid = player.getUUID();
        debug(player.getGameProfile().name() + " opening members GUI");
        clearGuiState(uuid);
        GUI_MEMBERS.add(uuid);

        UUID teamId = playerToTeam.get(uuid);
        if (teamId == null) return;
        TeamData team = teams.get(teamId);
        if (team == null) return;

        SimpleContainer container = new SimpleContainer(27);
        MinecraftServer server = player.level().getServer();

        List<UUID> orderedMembers = new ArrayList<>(team.memberUuids);
        GUI_MEMBERS_SLOTS.put(uuid, orderedMembers);

        // Place member heads in slots 10-16 (up to 7)
        int slot = 10;
        for (int i = 0; i < orderedMembers.size() && slot <= 16; i++, slot++) {
            UUID memberUuid = orderedMembers.get(i);
            String memberName = team.memberNames.getOrDefault(memberUuid, "Unknown");
            ServerPlayer memberPlayer = server.getPlayerList().getPlayer(memberUuid);
            boolean online = memberPlayer != null;
            boolean isLeader = team.leaderUuid.equals(memberUuid);

            ItemStack head = createPlayerHead(memberUuid, memberName);
            String roleTag = isLeader ? "\u00a76Leader" : "\u00a77Member";
            String statusTag = online ? " \u00a7a(Online)" : " \u00a7c(Offline)";
            head.set(DataComponents.CUSTOM_NAME,
                    Component.literal("\u00a7f\u00a7l" + memberName + " " + roleTag + statusTag));
            container.setItem(slot, head);
        }

        // Back button
        ItemStack back = new ItemStack(Items.ARROW);
        back.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7f\u00a7lBack"));
        container.setItem(22, back);

        fillEmpty(container, 27);
        openScreen(player, container, "\u00a78\u00a7lTeam Members");
    }

    // ------------------------------------------------------------------
    // GUI: Invite Player List
    // ------------------------------------------------------------------

    private static void openInviteListGui(ServerPlayer player, int page) {
        UUID uuid = player.getUUID();
        debug(player.getGameProfile().name() + " opening invite list GUI (page=" + page + ")");
        clearGuiState(uuid);
        GUI_INVITE_LIST.put(uuid, page);

        UUID teamId = playerToTeam.get(uuid);
        if (teamId == null) return;
        TeamData team = teams.get(teamId);
        if (team == null) return;

        MinecraftServer server = player.level().getServer();
        List<ServerPlayer> online = server.getPlayerList().getPlayers();

        // Filter: not on this team, no pending invite to this team
        List<UUID> candidates = new ArrayList<>();
        for (ServerPlayer op : online) {
            UUID opUuid = op.getUUID();
            if (team.memberUuids.contains(opUuid)) continue;
            PendingInvite existing = pendingInvites.get(opUuid);
            if (existing != null && existing.teamId().equals(teamId)) continue;
            candidates.add(opUuid);
        }

        int perPage = 18; // top 2 rows
        int start = page * perPage;
        int end = Math.min(start + perPage, candidates.size());
        List<UUID> pageItems = (start < candidates.size()) ? candidates.subList(start, end) : List.of();
        GUI_INVITE_SLOTS.put(uuid, new ArrayList<>(pageItems));

        SimpleContainer container = new SimpleContainer(27);

        for (int i = 0; i < pageItems.size(); i++) {
            UUID targetUuid = pageItems.get(i);
            ServerPlayer targetPlayer = server.getPlayerList().getPlayer(targetUuid);
            String targetName = targetPlayer != null ? targetPlayer.getGameProfile().name() : "Unknown";
            ItemStack head = createPlayerHead(targetUuid, targetName);
            head.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7f\u00a7l" + targetName));
            container.setItem(i, head);
        }

        // Back button
        ItemStack back = new ItemStack(Items.ARROW);
        back.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7f\u00a7lBack"));
        container.setItem(18, back);

        // Previous page
        if (page > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);
            prev.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7f\u00a7lPrevious Page"));
            container.setItem(19, prev);
        }

        // Next page
        if (end < candidates.size()) {
            ItemStack next = new ItemStack(Items.ARROW);
            next.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7f\u00a7lNext Page"));
            container.setItem(26, next);
        }

        fillEmpty(container, 27);
        openScreen(player, container, "\u00a78\u00a7lInvite Player");
    }

    // ------------------------------------------------------------------
    // GUI: Confirm Invite
    // ------------------------------------------------------------------

    private static void openConfirmInviteGui(ServerPlayer player, UUID targetUuid) {
        UUID uuid = player.getUUID();
        clearGuiState(uuid);
        GUI_CONFIRM_INVITE.put(uuid, targetUuid);

        MinecraftServer server = player.level().getServer();
        ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
        String targetName = target != null ? target.getGameProfile().name() : "Unknown";
        debug(player.getGameProfile().name() + " opening confirm invite GUI for " + targetName);

        SimpleContainer container = new SimpleContainer(27);

        ItemStack confirm = new ItemStack(Items.WOOL.lime());
        confirm.set(DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7a\u00a7lInvite " + targetName));
        container.setItem(11, confirm);

        ItemStack head = createPlayerHead(targetUuid, targetName);
        head.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7f\u00a7l" + targetName));
        container.setItem(13, head);

        ItemStack cancel = new ItemStack(Items.WOOL.red());
        cancel.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7c\u00a7lCancel"));
        container.setItem(15, cancel);

        fillEmpty(container, 27);
        openScreen(player, container, "\u00a78\u00a7lConfirm Invite");
    }

    // ------------------------------------------------------------------
    // GUI: Manage Member (leader only)
    // ------------------------------------------------------------------

    private static void openManageMemberGui(ServerPlayer player, UUID memberUuid) {
        UUID uuid = player.getUUID();
        clearGuiState(uuid);
        GUI_MANAGE.put(uuid, memberUuid);

        UUID teamId = playerToTeam.get(uuid);
        TeamData team = teamId != null ? teams.get(teamId) : null;
        String memberName = team != null ? team.memberNames.getOrDefault(memberUuid, "Unknown") : "Unknown";
        debug(player.getGameProfile().name() + " opening manage GUI for " + memberName);

        SimpleContainer container = new SimpleContainer(27);

        ItemStack head = createPlayerHead(memberUuid, memberName);
        head.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7f\u00a7l" + memberName));
        container.setItem(11, head);

        ItemStack kick = new ItemStack(Items.BARRIER);
        kick.set(DataComponents.CUSTOM_NAME,
                Component.literal("\u00a7c\u00a7lKick from Team"));
        container.setItem(15, kick);

        ItemStack back = new ItemStack(Items.ARROW);
        back.set(DataComponents.CUSTOM_NAME, Component.literal("\u00a7f\u00a7lBack"));
        container.setItem(22, back);

        fillEmpty(container, 27);
        openScreen(player, container, "\u00a78\u00a7lManage Member");
    }

    // ------------------------------------------------------------------
    // Click handling
    // ------------------------------------------------------------------

    public static boolean handleClick(ServerPlayer player, int slot) {
        UUID uuid = player.getUUID();

        // Confirm invite GUI
        if (GUI_CONFIRM_INVITE.containsKey(uuid)) {
            UUID targetUuid = GUI_CONFIRM_INVITE.get(uuid);
            if (slot == 11) {
                sendInvite(player, targetUuid);
                player.closeContainer();
                openMainGui(player);
            } else if (slot == 15) {
                player.closeContainer();
                openInviteListGui(player, 0);
            }
            return true;
        }

        // Manage member GUI
        if (GUI_MANAGE.containsKey(uuid)) {
            UUID memberUuid = GUI_MANAGE.get(uuid);
            if (slot == 15) {
                kickMember(player, memberUuid);
                player.closeContainer();
                openMembersGui(player);
            } else if (slot == 22) {
                player.closeContainer();
                openMembersGui(player);
            }
            return true;
        }

        // Members list GUI
        if (GUI_MEMBERS.contains(uuid)) {
            if (slot == 22) {
                player.closeContainer();
                openMainGui(player);
                return true;
            }
            // Click on a member head (slots 10-16)
            if (slot >= 10 && slot <= 16) {
                List<UUID> memberSlots = GUI_MEMBERS_SLOTS.get(uuid);
                int index = slot - 10;
                if (memberSlots != null && index < memberSlots.size()) {
                    UUID memberUuid = memberSlots.get(index);
                    UUID teamId = playerToTeam.get(uuid);
                    TeamData team = teamId != null ? teams.get(teamId) : null;
                    // Only leader can manage, and can't manage themselves
                    if (team != null && team.leaderUuid.equals(uuid) && !memberUuid.equals(uuid)) {
                        player.closeContainer();
                        openManageMemberGui(player, memberUuid);
                    }
                }
            }
            return true;
        }

        // Invite list GUI
        if (GUI_INVITE_LIST.containsKey(uuid)) {
            int page = GUI_INVITE_LIST.get(uuid);
            if (slot == 18) {
                // Back
                player.closeContainer();
                openMainGui(player);
            } else if (slot == 19 && page > 0) {
                // Previous page
                player.closeContainer();
                openInviteListGui(player, page - 1);
            } else if (slot == 26) {
                // Next page
                player.closeContainer();
                openInviteListGui(player, page + 1);
            } else if (slot >= 0 && slot <= 17) {
                // Player head click
                List<UUID> slotList = GUI_INVITE_SLOTS.get(uuid);
                if (slotList != null && slot < slotList.size()) {
                    UUID targetUuid = slotList.get(slot);
                    player.closeContainer();
                    openConfirmInviteGui(player, targetUuid);
                }
            }
            return true;
        }

        // Main menu GUI
        if (GUI_MAIN.contains(uuid)) {
            UUID teamId = playerToTeam.get(uuid);
            if (teamId == null) {
                // No team
                if (slot == 11) {
                    createTeam(player);
                    player.closeContainer();
                    openMainGui(player);
                } else if (slot == 15) {
                    if (pendingInvites.get(uuid) != null) {
                        player.sendSystemMessage(Component.literal(
                                "\u00a7eAccept or decline the invite in chat using the buttons above."));
                    }
                }
            } else {
                // Has team
                TeamData team = teams.get(teamId);
                if (slot == 12 && team != null && team.leaderUuid.equals(uuid)) {
                    // Friendly fire toggle (leader only)
                    team.friendlyFire = !team.friendlyFire;
                    save();
                    debug(player.getGameProfile().name() + " toggled friendly fire to " + team.friendlyFire);
                    player.closeContainer();
                    notifyTeam(server(), team, Component.literal(
                            team.friendlyFire
                                    ? "\u00a7cFriendly fire has been ENABLED."
                                    : "\u00a7aFriendly fire has been DISABLED."));
                    openMainGui(player);
                } else if (slot == 11) {
                    // Members
                    player.closeContainer();
                    openMembersGui(player);
                } else if (slot == 13 && team != null && team.leaderUuid.equals(uuid)) {
                    // Invite (leader only)
                    player.closeContainer();
                    openInviteListGui(player, 0);
                } else if (slot == 15) {
                    // Leave or Disband
                    if (team != null && team.leaderUuid.equals(uuid)) {
                        disbandTeam(player);
                    } else {
                        leaveTeam(player);
                    }
                    player.closeContainer();
                    openMainGui(player);
                }
            }
            return true;
        }

        return false;
    }

    // ------------------------------------------------------------------
    // Close / Cancel
    // ------------------------------------------------------------------

    public static void handleClose(ServerPlayer player) {
        UUID uuid = player.getUUID();
        boolean had = GUI_MAIN.remove(uuid) | GUI_MEMBERS.remove(uuid)
                | GUI_INVITE_LIST.remove(uuid) != null | GUI_CONFIRM_INVITE.remove(uuid) != null
                | GUI_MANAGE.remove(uuid) != null;
        GUI_INVITE_SLOTS.remove(uuid);
        GUI_MEMBERS_SLOTS.remove(uuid);
        if (had) {
            debug(player.getGameProfile().name() + " closed team GUI");
        }
    }

    public static void cancel(UUID playerUuid) {
        clearGuiState(playerUuid);
        pendingInvites.remove(playerUuid);
    }

    // ------------------------------------------------------------------
    // Tick — expire invites
    // ------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        if (pendingInvites.isEmpty()) return;
        int currentTick = server.getTickCount();
        Iterator<Map.Entry<UUID, PendingInvite>> it = pendingInvites.entrySet().iterator();
        while (it.hasNext()) {
            PendingInvite invite = it.next().getValue();
            if (currentTick >= invite.expiresAtTick()) {
                it.remove();
                ServerPlayer invitee = server.getPlayerList().getPlayer(invite.inviteeUuid());
                if (invitee != null) {
                    invitee.sendSystemMessage(Component.literal("\u00a7cTeam invite expired."));
                }
                debug("Invite for " + invite.inviteeUuid() + " expired");
            }
        }
    }

    // ------------------------------------------------------------------
    // Team actions
    // ------------------------------------------------------------------

    private static void createTeam(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();

        if (playerToTeam.containsKey(uuid)) {
            player.sendSystemMessage(Component.literal("\u00a7cYou are already in a team!"));
            return;
        }

        UUID teamId = UUID.randomUUID();
        TeamData team = new TeamData(teamId, uuid, name);
        teams.put(teamId, team);
        playerToTeam.put(uuid, teamId);
        save();

        debug(name + " created team " + teamId);
        player.sendSystemMessage(Component.literal("\u00a7aTeam created! You are the leader."));
    }

    private static void sendInvite(ServerPlayer leader, UUID targetUuid) {
        String leaderName = leader.getGameProfile().name();
        UUID teamId = playerToTeam.get(leader.getUUID());
        TeamData team = teamId != null ? teams.get(teamId) : null;

        if (team == null || !team.leaderUuid.equals(leader.getUUID())) {
            leader.sendSystemMessage(Component.literal("\u00a7cYou are not a team leader!"));
            return;
        }

        if (team.memberUuids.size() >= OsmiumConfig.teamMaxSize) {
            leader.sendSystemMessage(Component.literal("\u00a7cYour team is full! (" + OsmiumConfig.teamMaxSize + " max)"));
            return;
        }

        if (playerToTeam.containsKey(targetUuid)) {
            leader.sendSystemMessage(Component.literal("\u00a7cThat player is already in a team."));
            return;
        }

        MinecraftServer server = leader.level().getServer();
        ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);
        if (target == null) {
            leader.sendSystemMessage(Component.literal("\u00a7cThat player is no longer online."));
            return;
        }

        int expiresAt = server.getTickCount() + (OsmiumConfig.teamInviteTimeoutSeconds * 20);
        pendingInvites.put(targetUuid, new PendingInvite(leader.getUUID(), targetUuid, teamId, expiresAt));

        String targetName = target.getGameProfile().name();
        debug(leaderName + " invited " + targetName + " to team " + teamId);
        leader.sendSystemMessage(Component.literal(
                "\u00a7aInvite sent to \u00a7f" + targetName + "\u00a7a!"));
        target.sendSystemMessage(Component.literal(
                "\u00a7e" + leaderName + " invited you to their team!"));
        target.sendSystemMessage(Component.empty()
                .append(chatButton("[ACCEPT]", "/team accept", ChatFormatting.GREEN))
                .append(Component.literal("  "))
                .append(chatButton("[DENY]", "/team deny", ChatFormatting.RED)));
    }

    private static void acceptInvite(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        PendingInvite invite = pendingInvites.remove(uuid);
        clearGuiState(uuid);

        if (invite == null) {
            player.sendSystemMessage(Component.literal("\u00a7cInvite has expired."));
            return;
        }

        if (playerToTeam.containsKey(uuid)) {
            player.sendSystemMessage(Component.literal("\u00a7cYou are already in a team!"));
            return;
        }

        TeamData team = teams.get(invite.teamId());
        if (team == null) {
            player.sendSystemMessage(Component.literal("\u00a7cThat team no longer exists."));
            return;
        }

        if (team.memberUuids.size() >= OsmiumConfig.teamMaxSize) {
            player.sendSystemMessage(Component.literal("\u00a7cThat team is full!"));
            return;
        }

        team.memberUuids.add(uuid);
        team.memberNames.put(uuid, name);
        playerToTeam.put(uuid, invite.teamId());
        save();

        debug(name + " joined team " + invite.teamId());
        player.sendSystemMessage(Component.literal("\u00a7aYou joined the team!"));

        // Notify team members
        MinecraftServer server = player.level().getServer();
        for (UUID memberUuid : team.memberUuids) {
            if (memberUuid.equals(uuid)) continue;
            ServerPlayer member = server.getPlayerList().getPlayer(memberUuid);
            if (member != null) {
                member.sendSystemMessage(Component.literal(
                        "\u00a7a" + name + " joined the team!"));
            }
        }
    }

    private static void declineInvite(ServerPlayer player) {
        UUID uuid = player.getUUID();
        PendingInvite invite = pendingInvites.remove(uuid);
        clearGuiState(uuid);

        if (invite != null) {
            debug(player.getGameProfile().name() + " declined invite from " + invite.inviterUuid());
            MinecraftServer server = player.level().getServer();
            ServerPlayer inviter = server.getPlayerList().getPlayer(invite.inviterUuid());
            if (inviter != null) {
                inviter.sendSystemMessage(Component.literal(
                        "\u00a7c" + player.getGameProfile().name() + " declined your team invite."));
            }
        }

        player.sendSystemMessage(Component.literal("\u00a7cInvite declined."));
    }

    private static void kickMember(ServerPlayer leader, UUID memberUuid) {
        UUID teamId = playerToTeam.get(leader.getUUID());
        TeamData team = teamId != null ? teams.get(teamId) : null;

        if (team == null || !team.leaderUuid.equals(leader.getUUID())) {
            leader.sendSystemMessage(Component.literal("\u00a7cYou are not the team leader!"));
            return;
        }

        if (memberUuid.equals(leader.getUUID())) {
            leader.sendSystemMessage(Component.literal("\u00a7cYou can't kick yourself!"));
            return;
        }

        String memberName = team.memberNames.getOrDefault(memberUuid, "Unknown");
        team.memberUuids.remove(memberUuid);
        team.memberNames.remove(memberUuid);
        playerToTeam.remove(memberUuid);
        save();

        debug(leader.getGameProfile().name() + " kicked " + memberName + " from team");
        leader.sendSystemMessage(Component.literal(
                "\u00a7c" + memberName + " has been kicked from the team."));

        MinecraftServer server = leader.level().getServer();
        ServerPlayer kicked = server.getPlayerList().getPlayer(memberUuid);
        if (kicked != null) {
            kicked.sendSystemMessage(Component.literal(
                    "\u00a7cYou have been kicked from the team."));
        }
    }

    private static void leaveTeam(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        UUID teamId = playerToTeam.get(uuid);
        TeamData team = teamId != null ? teams.get(teamId) : null;

        if (team == null) {
            player.sendSystemMessage(Component.literal("\u00a7cYou are not in a team!"));
            return;
        }

        team.memberUuids.remove(uuid);
        team.memberNames.remove(uuid);
        playerToTeam.remove(uuid);
        save();

        debug(name + " left team " + teamId);
        player.sendSystemMessage(Component.literal("\u00a7cYou left the team."));

        // Notify remaining members
        MinecraftServer server = player.level().getServer();
        for (UUID memberUuid : team.memberUuids) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberUuid);
            if (member != null) {
                member.sendSystemMessage(Component.literal(
                        "\u00a7c" + name + " left the team."));
            }
        }
    }

    private static void disbandTeam(ServerPlayer leader) {
        UUID uuid = leader.getUUID();
        UUID teamId = playerToTeam.get(uuid);
        TeamData team = teamId != null ? teams.get(teamId) : null;

        if (team == null || !team.leaderUuid.equals(uuid)) {
            leader.sendSystemMessage(Component.literal("\u00a7cYou are not the team leader!"));
            return;
        }

        String leaderName = leader.getGameProfile().name();
        debug(leaderName + " disbanded team " + teamId);

        // Notify and remove all members
        MinecraftServer server = leader.level().getServer();
        for (UUID memberUuid : team.memberUuids) {
            playerToTeam.remove(memberUuid);
            if (!memberUuid.equals(uuid)) {
                ServerPlayer member = server.getPlayerList().getPlayer(memberUuid);
                if (member != null) {
                    member.sendSystemMessage(Component.literal(
                            "\u00a7c" + leaderName + " disbanded the team."));
                }
            }
        }

        teams.remove(teamId);
        save();

        leader.sendSystemMessage(Component.literal("\u00a7cTeam disbanded."));
    }

    // ------------------------------------------------------------------
    // Util
    // ------------------------------------------------------------------

    private static void clearGuiState(UUID uuid) {
        GUI_MAIN.remove(uuid);
        GUI_MEMBERS.remove(uuid);
        GUI_INVITE_LIST.remove(uuid);
        GUI_INVITE_SLOTS.remove(uuid);
        GUI_CONFIRM_INVITE.remove(uuid);
        GUI_MANAGE.remove(uuid);
        GUI_MEMBERS_SLOTS.remove(uuid);
    }

    private static void fillEmpty(SimpleContainer container, int size) {
        ItemStack filler = new ItemStack(Items.STAINED_GLASS_PANE.gray());
        filler.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        for (int i = 0; i < size; i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, filler.copy());
            }
        }
    }

    private static void openScreen(ServerPlayer player, SimpleContainer container, String title) {
        int containerId = player.nextContainerCounter();
        ChestMenu menu = ChestMenu.threeRows(containerId, player.getInventory(), container);
        menu.setTitle(Component.literal(title));
        player.connection.send(new ClientboundOpenScreenPacket(
                containerId, MenuType.GENERIC_9x3, Component.literal(title)));
        player.containerMenu = menu;
        player.initMenu(menu);
    }

    private static ItemStack createPlayerHead(UUID playerUuid, String playerName) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(playerUuid));
        return head;
    }
}
