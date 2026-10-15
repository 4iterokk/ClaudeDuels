package org.chiterok.grandDuels.kit;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.config.Settings;
import org.chiterok.grandDuels.match.MatchSettings;
import org.chiterok.grandDuels.match.PlayerSnapshot;
import org.chiterok.grandDuels.utils.StoredLocation;
import org.chiterok.grandDuels.utils.TimeUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Kit editing sessions in creative mode. Two kinds exist:
 * <ul>
 *   <li><b>Admin sessions</b> ({@code /duels kit create|edit}): trusted, unrestricted creative mode.</li>
 *   <li><b>Player sessions</b> ({@code /duel kit create|edit}): <i>restricted</i> creative mode. The player cannot
 *   leave the editing area, change the world, touch other players or entities, drop items, run commands or stay
 *   longer than the configured timeout, and every item is validated when the kit is saved
 *   (see {@link PlayerKitItemFilter}). The restrictions are enforced by
 *   {@link org.chiterok.grandDuels.runtime.KitEditorListener}.</li>
 * </ul>
 * The player's real state is snapshotted (and persisted for crash recovery) when a session starts and restored when it
 * ends, however it ends: save, cancel, timeout, quit, kick, death or plugin shutdown. Main thread only.
 */
public final class KitEditManager {

    public enum StartResult { STARTED, IN_DUEL, ALREADY_EDITING, FAILED }

    private static final class Session {
        private final String kitId;
        private final PlayerSnapshot snapshot;
        private final Location area;
        private final boolean playerKit;
        private final long deadlineMillis;
        private @Nullable BukkitTask task;

        private Session(String kitId, PlayerSnapshot snapshot, Location area, boolean playerKit, long deadlineMillis) {
            this.kitId = kitId;
            this.snapshot = snapshot;
            this.area = area;
            this.playerKit = playerKit;
            this.deadlineMillis = deadlineMillis;
        }
    }

    private final ClaudeDuels plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public KitEditManager(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    public boolean isEditing(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    /** True for player sessions: these are the ones with the creative restrictions. */
    public boolean isRestricted(UUID playerId) {
        Session session = sessions.get(playerId);
        return session != null && session.playerKit;
    }

    /** True while an admin session edits the standard kit {@code kitId} (player kits have their own namespace). */
    public boolean isEditingKit(String kitId) {
        for (Session session : sessions.values()) {
            if (!session.playerKit && session.kitId.equalsIgnoreCase(kitId)) return true;
        }
        return false;
    }

    public @Nullable String editedKit(UUID playerId) {
        Session session = sessions.get(playerId);
        return session == null ? null : session.kitId;
    }

    /**
     * The centre of the editing area, or {@code null}: the configured editor zone when the player was teleported
     * there, otherwise where the player stood when the session began.
     */
    public @Nullable Location origin(UUID playerId) {
        Session session = sessions.get(playerId);
        return session == null ? null : session.area;
    }

    // ---------------------------------------------------------------- admin sessions

    /** Starts editing {@code kitId}; {@code existing == null} starts from an empty loadout (new kit). */
    public StartResult start(Player player, String kitId, @Nullable Kit existing) {
        UUID id = player.getUniqueId();
        if (plugin.isOccupied(id)) return plugin.kitEdits().isEditing(id) ? StartResult.ALREADY_EDITING : StartResult.IN_DUEL;

        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        if (!plugin.snapshots().save(id, snapshot)) return StartResult.FAILED;
        sessions.put(id, new Session(kitId, snapshot, snapshot.location(), false, 0L));

        player.closeInventory();
        clearEffects(player);
        player.getInventory().clear();
        if (existing != null) existing.apply(player, new MatchSettings(false, Map.of(), Set.of()));
        player.setGameMode(GameMode.CREATIVE);
        return StartResult.STARTED;
    }

    /**
     * Saves the current inventory, armor, offhand and effects as kit {@code targetId} (the edited kit when
     * {@code null}) and restores the player.
     * @return the id the kit was saved as, or {@code null} when the player is not in an admin session.
     */
    public @Nullable String save(Player player, @Nullable String targetId) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.playerKit) return null;
        String id = targetId == null ? session.kitId : targetId;
        plugin.kits().save(id, player);
        finish(player, session, true);
        return id;
    }

    // ---------------------------------------------------------------- player sessions

    /**
     * Starts a restricted creative session for a player-made kit.
     * @param existing the kit to edit, or {@code null} to start from what the player carries right now (new kit)
     */
    public StartResult startPlayerSession(Player player, String kitId, @Nullable Kit existing) {
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) return StartResult.ALREADY_EDITING;
        if (plugin.isOccupied(id)) return StartResult.IN_DUEL;

        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        if (!plugin.snapshots().save(id, snapshot)) return StartResult.FAILED;

        Settings.KitEditorSettings editor = plugin.settings().playerKits().editor();
        long deadline = editor.timeoutSeconds() <= 0 ? 0L : System.currentTimeMillis() + editor.timeoutSeconds() * 1000L;
        Location zone = resolveZone(editor);
        Session session = new Session(kitId, snapshot, zone != null ? zone : snapshot.location(), true, deadline);
        sessions.put(id, session);

        plugin.queues().leave(id);
        plugin.requests().purge(id);
        player.closeInventory();
        player.leaveVehicle();
        clearEffects(player);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        if (existing != null) {
            player.getInventory().clear();
            existing.apply(player, new MatchSettings(false, Map.of(), Set.of()));
        }
        player.setGameMode(GameMode.CREATIVE);
        if (zone != null) player.teleport(zone);
        session.task = new BukkitRunnable() {
            @Override
            public void run() {
                tick(player, session);
            }
        }.runTaskTimer(plugin, 20L, 20L);
        return StartResult.STARTED;
    }

    /** The configured editor zone, or {@code null} when none is set or its world is not loaded. */
    private @Nullable Location resolveZone(Settings.KitEditorSettings editor) {
        StoredLocation zone = editor.zone();
        if (zone == null) return null;
        Location location = zone.toLocation();
        if (location == null) {
            plugin.getLogger().warning("player-kits.editor.zone: world '" + zone.world()
                    + "' is not loaded, kit editors stay where they are");
        }
        return location;
    }

    private void tick(Player player, Session session) {
        if (sessions.get(player.getUniqueId()) != session || !player.isOnline()) {
            if (session.task != null) session.task.cancel();
            return;
        }
        if (session.deadlineMillis > 0L && System.currentTimeMillis() >= session.deadlineMillis) {
            finish(player, session, true);
            plugin.messages().send(player, "playerkits.editor-timeout");
            return;
        }
        String time = session.deadlineMillis <= 0L ? "-"
                : TimeUtil.mmss(Math.max(0L, (session.deadlineMillis - System.currentTimeMillis()) / 1000L));
        plugin.messages().actionBar(player, "playerkits.editor-status", "kit", session.kitId, "time", time);
    }

    /**
     * Saves the creative loadout of a player session as a player kit. The session only ends when the kit was saved;
     * on failure (empty loadout, kit limit, ...) the player keeps editing and can fix it.
     * @param targetName kit name to save as, {@code null} for the kit that is being edited
     * @return the outcome, or {@code null} when the player is not in a player session
     */
    public @Nullable PlayerKitManager.SaveOutcome savePlayerKit(Player player, @Nullable String targetName) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.playerKit) return null;
        String id = targetName == null ? session.kitId : targetName;
        boolean overwrite = id.equalsIgnoreCase(session.kitId);
        PlayerKitManager.SaveOutcome outcome = plugin.playerKits().saveEdited(player, id, overwrite);
        if (outcome.result() == PlayerKitManager.SaveResult.CREATED
                || outcome.result() == PlayerKitManager.SaveResult.UPDATED) {
            finish(player, session, true);
        }
        return outcome;
    }

    // ---------------------------------------------------------------- shared

    /** Ends the session without saving. */
    public boolean cancel(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return false;
        finish(player, session, true);
        return true;
    }

    /** Ends only player sessions (used by {@code /duel kit cancel}); admin sessions have their own command. */
    public boolean cancelPlayerSession(Player player) {
        return isRestricted(player.getUniqueId()) && cancel(player);
    }

    /** Quit handler: the player is leaving, so restore synchronously and discard the session. */
    public void handleQuit(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) finish(player, session, false);
    }

    /** Plugin disable: restore every editor synchronously. */
    public void shutdown() {
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(sessions.entrySet())) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) finish(player, entry.getValue(), false);
        }
        sessions.clear();
    }

    /**
     * Ends a session and gives the player their real state back. The session is removed first so that the editing
     * restrictions do not block the restoring teleport.
     */
    private void finish(Player player, Session session, boolean async) {
        sessions.remove(player.getUniqueId());
        if (session.task != null) session.task.cancel();
        session.snapshot.restore(player, session.snapshot.location(), async);
        plugin.snapshots().delete(player.getUniqueId());
    }

    private static void clearEffects(Player player) {
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
    }
}
