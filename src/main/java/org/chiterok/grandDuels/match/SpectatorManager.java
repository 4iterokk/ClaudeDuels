package org.chiterok.grandDuels.match;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.utils.StoredLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spectating of running duels ({@code /spec}). A spectator is put into spectator mode next to the duel; their complete
 * previous state (inventory, gamemode, flight, location, scoreboard, ...) is snapshotted and persisted first, exactly
 * like for duelists, so a crash can never leave anybody stuck in spectator mode: the leftover snapshot is restored on
 * the next join ({@link MatchManager#recover}).
 * <p>
 * A session ends, and the player gets their previous state back, when: they run {@code /spec leave}, the duel ends,
 * they quit or are kicked, they die (e.g. {@code /kill}), or the plugin is disabled. Spectators are confined to the
 * duel's area (see {@code spectate.padding}); the rules are enforced by
 * {@link org.chiterok.grandDuels.runtime.SpectatorListener}. Main thread only.
 */
public final class SpectatorManager {

    public enum JoinResult {
        /** Started spectating; the player is being moved to the duel. */
        JOINED,
        /** Already spectating another duel (or player): moved to the new one. */
        MOVED,
        DISABLED,
        /** In a duel, the arena or the kit editor, or dying. */
        BUSY,
        DEAD,
        MATCH_ENDED,
        FULL,
        FAILED;

        public boolean success() {
            return this == JOINED || this == MOVED;
        }
    }

    public enum LeaveResult { LEFT, NOT_SPECTATING }

    private static final class Session {
        private final PlayerSnapshot snapshot;
        private Match match;
        /** Number of teleports into a duel that have not completed yet. */
        private int moves;
        /** The player stands at the duel in spectator mode (the movement rules apply). */
        private boolean arrived;
        private boolean greeted;
        /** Leaving was requested while a teleport into the duel was still in flight. */
        private boolean endRequested;
        private @Nullable String endMessage;
        /** The player died; they are respawned and then restored. */
        private boolean dying;

        private Session(PlayerSnapshot snapshot, Match match) {
            this.snapshot = snapshot;
            this.match = match;
        }
    }

    /** Ticks to wait after a respawn before moving the player again (the client must finish loading). */
    private static final long RESPAWN_SETTLE_TICKS = 10L;
    private static final int RESPAWN_RETRIES = 6;
    private static final long TICK_INTERVAL = 20L;

    private final ClaudeDuels plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private BukkitTask task;

    public SpectatorManager(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    /** Starts the once-a-second housekeeping (action bar, safety nets). Call once on enable. */
    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    // ---------------------------------------------------------------- queries

    /** True from the moment a player asked to spectate until they are restored. */
    public boolean isSpectating(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    /** True while the player is a spectator at the duel (not while being moved there or dying). */
    public boolean isConfined(UUID playerId) {
        Session session = sessions.get(playerId);
        return session != null && session.arrived && !session.dying;
    }

    public @Nullable Match matchOf(UUID playerId) {
        Session session = sessions.get(playerId);
        return session == null ? null : session.match;
    }

    public int count() {
        return sessions.size();
    }

    public int watchers(Match match) {
        int total = 0;
        for (Session session : sessions.values()) {
            if (session.match == match) total++;
        }
        return total;
    }

    /** {@link Match#placeholders()} plus {spectators}. */
    public Map<String, String> describe(Match match) {
        Map<String, String> values = new HashMap<>(match.placeholders());
        values.put("spectators", String.valueOf(watchers(match)));
        return values;
    }

    /** Whether the location is inside the duel's area plus {@code spectate.padding}. */
    public boolean permits(Match match, Location location) {
        BoundingBox bounds = match.arena().activeBounds();
        if (bounds == null) return true;
        if (location.getWorld() == null || !location.getWorld().getName().equals(match.arena().worldName())) return false;
        return bounds.clone().expand(plugin.settings().spectate().padding())
                .contains(location.getX(), location.getY(), location.getZ());
    }

    /** Whether a teleport of the player to {@code location} is allowed (always true for non-spectators). */
    public boolean permits(Player player, Location location) {
        Session session = sessions.get(player.getUniqueId());
        return session == null || permits(session.match, location);
    }

    /** A safe place at the duel to put a spectator back to, or {@code null} when the arena has no spawn. */
    public @Nullable Location returnPoint(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session == null ? null : destination(session.match, session.match.participants().get(0));
    }

    // ---------------------------------------------------------------- join / leave

    /**
     * Starts spectating {@code match}, or moves an existing spectator there. The player is teleported next to
     * {@code focus} (a duelist). Failures are reported to the player.
     */
    public JoinResult watch(Player player, Match match, UUID focus) {
        JoinResult result = tryWatch(player, match, focus);
        if (!result.success()) plugin.messages().send(player, failureMessage(result));
        return result;
    }

    private static String failureMessage(JoinResult result) {
        return switch (result) {
            case DISABLED -> "spectate.disabled";
            case BUSY -> "spectate.busy";
            case DEAD -> "spectate.dead";
            case MATCH_ENDED -> "spectate.match-ended";
            case FULL -> "spectate.full";
            default -> "spectate.failed";
        };
    }

    private JoinResult tryWatch(Player player, Match match, UUID focus) {
        if (!plugin.settings().spectate().enabled()) return JoinResult.DISABLED;
        UUID id = player.getUniqueId();
        if (player.isDead()) return JoinResult.DEAD;
        if (match.phase() == Match.Phase.FINISHED) return JoinResult.MATCH_ENDED;
        if (match.participants().contains(id)) return JoinResult.BUSY;

        Session existing = sessions.get(id);
        if (existing != null && existing.dying) return JoinResult.BUSY;
        if (existing == null && (plugin.matches().isInMatch(id) || plugin.kitEdits().isEditing(id)
                || plugin.arenaMode().isIn(id))) {
            return JoinResult.BUSY;
        }
        int max = plugin.settings().spectate().maxPerMatch();
        if (max > 0 && (existing == null || existing.match != match) && watchers(match) >= max) return JoinResult.FULL;

        Location destination = destination(match, focus);
        if (destination == null) return JoinResult.FAILED;

        if (existing != null) {
            existing.match = match;
            existing.arrived = false;
            existing.endRequested = false; // a new request overrides an earlier "leave" that is still waiting
            existing.endMessage = null;
            moveTo(player, existing, destination);
            return JoinResult.MOVED;
        }

        // Close first: the cursor item returns to the inventory, which the snapshot must contain.
        player.closeInventory();
        player.leaveVehicle();
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        if (!plugin.snapshots().save(id, snapshot)) return JoinResult.FAILED;

        Session session = new Session(snapshot, match);
        sessions.put(id, session);
        plugin.queues().leave(id);
        plugin.requests().purge(id);
        moveTo(player, session, destination);
        return JoinResult.JOINED;
    }

    /** {@code /spec leave}: gives the player their previous state back. */
    public LeaveResult leave(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.dying) return LeaveResult.NOT_SPECTATING;
        requestEnd(player, session, null);
        return LeaveResult.LEFT;
    }

    /**
     * Ends the sessions of a finished duel (message + restore). {@code async=false} is for plugin shutdown: no
     * scheduler calls, players that are dead are left to the snapshot recovery on their next join.
     */
    public void releaseAll(Match match, boolean async) {
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(sessions.entrySet())) {
            Session session = entry.getValue();
            if (session.match != match) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                sessions.remove(entry.getKey(), session); // the snapshot file stays: restored on the next join
            } else if (async) {
                requestEnd(player, session, "spectate.ended");
            } else if (player.isDead()) {
                sessions.remove(entry.getKey(), session);
            } else {
                restore(player, session, false);
            }
        }
    }

    // ---------------------------------------------------------------- quit, death, shutdown

    /** Quit handler: the player is leaving, so restore synchronously. */
    public void handleQuit(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) restore(player, session, false);
    }

    /** Death listener (the player can only die to /kill or similar): respawn, then restore. */
    public void handleDeath(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.dying) return;
        session.dying = true;
        awaitRespawn(player, session, RESPAWN_RETRIES);
    }

    /** Where a dying spectator respawns: at the duel (loaded chunks); the move back happens right after. */
    public @Nullable Location respawnLocation(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session == null ? null : session.match.arena().location1();
    }

    /** Plugin disable: return everyone synchronously. */
    public void shutdown() {
        if (task != null) task.cancel();
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && !player.isDead()) restore(player, entry.getValue(), false);
        }
        sessions.clear();
    }

    // ---------------------------------------------------------------- internals

    private void moveTo(Player player, Session session, Location destination) {
        session.moves++;
        player.teleportAsync(destination).whenComplete((ok, error) ->
                Bukkit.getScheduler().runTask(plugin, () ->
                        arrived(player, session, error == null && Boolean.TRUE.equals(ok))));
    }

    private void arrived(Player player, Session session, boolean success) {
        UUID id = player.getUniqueId();
        if (sessions.get(id) != session) return; // quit / shutdown restored the player meanwhile
        session.moves = Math.max(0, session.moves - 1);
        if (!player.isOnline() || session.dying) return;
        if (session.moves > 0) return; // a newer teleport is in flight and reports back itself

        if (session.endRequested) {
            if (session.endMessage != null) plugin.messages().send(player, session.endMessage);
            restoreWhenAlive(player, session);
            return;
        }
        if (!success) {
            requestEnd(player, session, "spectate.failed");
            return;
        }
        Match match = session.match;
        if (match.phase() == Match.Phase.FINISHED) {
            requestEnd(player, session, "spectate.ended");
            return;
        }
        session.arrived = true;
        player.setGameMode(GameMode.SPECTATOR);
        match.showScoreboardTo(player);
        plugin.messages().send(player, session.greeted ? "spectate.moved" : "spectate.joined", describe(match));
        session.greeted = true;
    }

    /**
     * Ends a session: immediately, or as soon as the teleport that is still in flight completed (restoring first
     * would let that teleport win and strand the player at the duel in their normal gamemode).
     */
    private void requestEnd(Player player, Session session, @Nullable String messageKey) {
        if (sessions.get(player.getUniqueId()) != session || session.dying) return;
        if (session.moves > 0) {
            session.endRequested = true;
            if (session.endMessage == null) session.endMessage = messageKey;
            return;
        }
        if (messageKey != null) plugin.messages().send(player, messageKey);
        restoreWhenAlive(player, session);
    }

    private void restoreWhenAlive(Player player, Session session) {
        if (sessions.get(player.getUniqueId()) != session) return;
        if (!player.isDead()) {
            restore(player, session, true);
            return;
        }
        session.dying = true;
        awaitRespawn(player, session, RESPAWN_RETRIES);
    }

    private void awaitRespawn(Player player, Session session, int attemptsLeft) {
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || sessions.get(id) != session) return;
            if (player.isDead()) player.spigot().respawn();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || sessions.get(id) != session) return;
                if (player.isDead()) {
                    if (attemptsLeft > 0) awaitRespawn(player, session, attemptsLeft - 1);
                    return;
                }
                restore(player, session, true);
            }, RESPAWN_SETTLE_TICKS);
        });
    }

    private void restore(Player player, Session session, boolean async) {
        UUID id = player.getUniqueId();
        if (!sessions.remove(id, session)) return; // removing first: the teleport/gamemode guards no longer apply
        session.snapshot.restore(player, destinationFor(session.snapshot), async);
        plugin.snapshots().delete(id);
    }

    /** Where the player was before spectating; the lobby, then the main world's spawn if that world is gone. */
    private Location destinationFor(PlayerSnapshot snapshot) {
        Location previous = snapshot.location();
        if (previous != null) return previous;
        StoredLocation lobby = plugin.settings().duel().lobby();
        if (lobby != null) {
            Location resolved = lobby.toLocation();
            if (resolved != null) return resolved;
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    /** Next to the focused duelist when they are inside the area, otherwise their spawn point. */
    private @Nullable Location destination(Match match, UUID focus) {
        Player duelist = match.participants().contains(focus) ? Bukkit.getPlayer(focus) : null;
        if (duelist != null && duelist.isOnline()) {
            Location at = duelist.getLocation();
            if (permits(match, at)) return at;
        }
        Location spawn = match.spawnOf(focus);
        return spawn != null ? spawn : match.arena().location1();
    }

    private void tick() {
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(sessions.entrySet())) {
            UUID id = entry.getKey();
            Session session = entry.getValue();
            if (sessions.get(id) != session) continue;
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                sessions.remove(id, session); // the snapshot file stays: restored on the next join
                continue;
            }
            if (session.dying) continue;
            Match match = session.match;
            if (match.phase() == Match.Phase.FINISHED) { // safety net: the duel ended without releasing us
                requestEnd(player, session, "spectate.ended");
                continue;
            }
            if (!session.arrived) continue;
            if (!permits(match, player.getLocation())) { // safety net: something moved the spectator away
                Location back = destination(match, match.participants().get(0));
                if (back != null) player.teleportAsync(back);
            }
            plugin.messages().actionBar(player, "spectate.actionbar", describe(match));
        }
    }
}
