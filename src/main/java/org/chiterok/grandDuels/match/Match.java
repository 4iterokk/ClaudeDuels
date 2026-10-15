package org.chiterok.grandDuels.match;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.Arena;
import org.chiterok.grandDuels.arena.ArenaState;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.config.Settings;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.utils.StoredLocation;
import org.chiterok.grandDuels.utils.TimeUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One running 1v1 duel: countdown, fight, celebration and restoration.
 * Everything runs on the main thread. The arena state mirrors the phase:
 * COUNTDOWN=STARTING, FIGHTING=IN_GAME, ENDING/FINISHED=RESETTING until the rollback completes.
 */
public final class Match {

    public enum Phase { COUNTDOWN, FIGHTING, ENDING, FINISHED }

    /** Ticks to wait after a respawn before moving/teleporting the player again (client must finish loading). */
    private static final long RESPAWN_SETTLE_TICKS = 10L;

    private final ClaudeDuels plugin;
    private final MatchManager manager;
    private final Arena arena;
    private final Kit kit;
    private final MatchSettings settings;
    private final DuelMode mode;
    private final UUID id1;
    private final UUID id2;
    private final String name1;
    private final String name2;
    private final Map<UUID, PlayerSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<UUID, Double> damageDealt = new HashMap<>();
    private final Set<UUID> restored = new HashSet<>();
    private final MatchScoreboard scoreboard;
    private final Map<UUID, Long> respawnTimes = new HashMap<>();

    private Phase phase = Phase.COUNTDOWN;
    private BukkitTask countdownTask;
    private BukkitTask tickTask;
    private BukkitTask celebrationTask;
    private int elapsedSeconds;

    Match(ClaudeDuels plugin, MatchManager manager, Arena arena, Kit kit, MatchSettings settings, DuelMode mode,
          Player first, Player second) {
        this.plugin = plugin;
        this.manager = manager;
        this.arena = arena;
        this.kit = kit;
        this.settings = settings;
        this.mode = mode;
        this.id1 = first.getUniqueId();
        this.id2 = second.getUniqueId();
        this.name1 = first.getName();
        this.name2 = second.getName();
        this.scoreboard = new MatchScoreboard(plugin.messages());
    }

    // ------------------------------------------------------------------ accessors

    public Arena arena() {
        return arena;
    }

    public Kit kit() {
        return kit;
    }

    public DuelMode mode() {
        return mode;
    }

    public MatchSettings settings() {
        return settings;
    }

    public Phase phase() {
        return phase;
    }

    public boolean isFighting() {
        return phase == Phase.FIGHTING;
    }

    public boolean isFrozen() {
        return phase == Phase.COUNTDOWN;
    }

    public List<UUID> participants() {
        return List.of(id1, id2);
    }

    public UUID opponentOf(UUID id) {
        return id.equals(id1) ? id2 : id1;
    }

    public boolean usesCustomCooldowns() {
        return settings.customCooldowns();
    }

    public void addDamage(UUID attacker, double amount) {
        if (phase == Phase.FIGHTING && amount > 0) damageDealt.merge(attacker, amount, Double::sum);
    }

    /** Spawn point of the player's own side (also used as the respawn point while the duel is ending). */
    public @Nullable Location spawnOf(UUID id) {
        return id.equals(id1) ? arena.location1() : arena.location2();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Captures and persists snapshots, prepares both players and starts the countdown. */
    boolean begin(Player first, Player second) {
        Location spawn1 = arena.location1();
        Location spawn2 = arena.location2();
        if (spawn1 == null || spawn2 == null) return false;

        for (Player p : List.of(first, second)) {
            PlayerSnapshot snapshot = PlayerSnapshot.capture(p);
            if (!plugin.snapshots().save(p.getUniqueId(), snapshot)) {
                for (UUID saved : snapshots.keySet()) plugin.snapshots().delete(saved);
                snapshots.clear();
                return false;
            }
            snapshots.put(p.getUniqueId(), snapshot);
        }

        Location destination1 = prepare(first, spawn1, spawn2);
        Location destination2 = prepare(second, spawn2, spawn1);
        scoreboard.update(placeholders());

        for (Player p : List.of(first, second)) {
            Player other = p == first ? second : first;
            plugin.messages().send(p, "duels.starting", "opponent", other.getName(), "arena", arena.displayName(),
                    "kit", kit.displayName());
        }
        teleportThenCountdown(first, destination1, second, destination2);
        return true;
    }

    /**
     * Moves both players with an async teleport (the arena chunks are loaded off the main thread and the client gets
     * a normal world transfer) and starts the countdown only once both have arrived. A synchronous teleport into
     * unloaded chunks, right after the queue menu closed, could leave clients on "Loading terrain...".
     */
    private void teleportThenCountdown(Player first, Location destination1, Player second, Location destination2) {
        CompletableFuture<Boolean> arrival1 = first.teleportAsync(destination1);
        CompletableFuture<Boolean> arrival2 = second.teleportAsync(destination2);
        CompletableFuture.allOf(arrival1, arrival2).whenComplete((ignored, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (phase != Phase.COUNTDOWN) return; // ended (quit, forced end) while teleporting
                    retryIfFailed(first, destination1, arrival1);
                    retryIfFailed(second, destination2, arrival2);
                    startCountdown();
                }));
    }

    private static void retryIfFailed(Player player, Location destination, CompletableFuture<Boolean> arrival) {
        boolean arrived = !arrival.isCompletedExceptionally() && arrival.getNow(false);
        if (!arrived && player.isOnline()) player.teleport(destination);
    }

    private Location prepare(Player player, Location spawn, Location lookAt) {
        player.closeInventory();
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setLevel(0);
        player.setExp(0.0f);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        plugin.cooldowns().clear(player);
        kit.apply(player, settings);
        player.getInventory().setHeldItemSlot(0);

        Location destination = spawn.clone();
        destination.setDirection(lookAt.toVector().subtract(spawn.toVector()));
        scoreboard.show(player);
        return destination;
    }

    private void startCountdown() {
        int seconds = plugin.settings().duel().countdownSeconds();
        if (seconds <= 0) {
            startFight();
            return;
        }
        countdownTask = new BukkitRunnable() {
            private int remaining = seconds;

            @Override
            public void run() {
                if (phase != Phase.COUNTDOWN) {
                    cancel();
                    return;
                }
                if (remaining <= 0) {
                    cancel();
                    startFight();
                    return;
                }
                for (Player p : onlineParticipants()) {
                    plugin.messages().title(p, "countdown", Messages.ph("seconds", remaining), 0, 25, 5);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                }
                remaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void startFight() {
        phase = Phase.FIGHTING;
        arena.setState(ArenaState.IN_GAME);
        for (Player p : onlineParticipants()) {
            plugin.messages().title(p, "go", Map.of(), 0, 20, 10);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
        }
        int limit = plugin.settings().duel().maxDurationSeconds();
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (phase != Phase.FIGHTING) {
                    cancel();
                    return;
                }
                elapsedSeconds++;
                tick();
                if (limit > 0 && elapsedSeconds >= limit) end(null, null, false);
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void tick() {
        scoreboard.update(placeholders());
        String time = TimeUtil.mmss(elapsedSeconds);
        for (Player p : onlineParticipants()) {
            Player opponent = Bukkit.getPlayer(opponentOf(p.getUniqueId()));
            String health = opponent == null ? "0" : hearts(opponent);
            plugin.messages().actionBar(p, "duels.ongoing",
                    "opponent", opponent == null ? "-" : opponent.getName(), "health", health, "time", time);
        }
    }

    // ------------------------------------------------------------------ ending

    /** Called from the death listener. */
    public void handleDeath(Player victim) {
        if (phase != Phase.FIGHTING) return;
        Player winner = Bukkit.getPlayer(opponentOf(victim.getUniqueId()));
        end(winner, victim, false);
    }

    /** Called from PlayerQuitEvent (the player is still online). */
    public void handleQuit(Player quitter) {
        if (phase == Phase.FINISHED) return;
        Player other = Bukkit.getPlayer(opponentOf(quitter.getUniqueId()));
        if (other != null) {
            plugin.messages().send(other, "duels.opponent-left", "opponent", quitter.getName());
        }
        restoreNow(quitter, false);
        switch (phase) {
            case FIGHTING -> end(other, quitter, true);
            case COUNTDOWN -> {
                phase = Phase.ENDING;
                cancelTasks();
                arena.setState(ArenaState.RESETTING);
                finish();
            }
            default -> { /* already ending; finish() skips the restored quitter */ }
        }
    }

    /** Admin command: ends the duel as a draw. */
    public void forceEnd() {
        if (phase == Phase.FIGHTING) {
            end(null, null, false);
        } else if (phase == Phase.COUNTDOWN) {
            phase = Phase.ENDING;
            cancelTasks();
            arena.setState(ArenaState.RESETTING);
            finish();
        }
    }

    private void end(@Nullable Player winner, @Nullable Player loser, boolean loserLeft) {
        if (phase == Phase.ENDING || phase == Phase.FINISHED) return;
        phase = Phase.ENDING;
        cancelTasks();
        arena.setState(ArenaState.RESETTING);

        Messages m = plugin.messages();
        if (winner != null && loser != null) {
            boolean ranked = mode == DuelMode.RANKED;
            // kit rating mode: ranked duels move the ELO of the duel's kit instead of the global ELO
            boolean kitRated = ranked && kit.owner() == null
                    && plugin.settings().matchmaking().ratingMode() == Settings.RatingMode.KIT;
            CompletableFuture<Integer> statsResult = plugin.stats().recordResult(winner, loser, ranked && !kitRated);
            CompletableFuture<Integer> ratingResult = kitRated
                    ? plugin.kitRatings().recordResult(winner, loser, kit.id()) : statsResult;
            ratingResult.thenAccept(change -> {
                if (!ranked || change <= 0) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (winner.isOnline()) plugin.messages().send(winner, "duels.elo-gain", "amount", change);
                    if (loser.isOnline()) plugin.messages().send(loser, "duels.elo-loss", "amount", change);
                });
            });
            m.title(winner, "victory", Messages.ph("opponent", loser.getName()), 5, 60, 10);
            m.send(winner, "duels.summary", "damage", fmt(damageDealt.getOrDefault(winner.getUniqueId(), 0.0)),
                    "opponent_damage", fmt(damageDealt.getOrDefault(loser.getUniqueId(), 0.0)));
            if (!loserLeft) {
                m.title(loser, "defeat", Messages.ph("opponent", winner.getName()), 5, 60, 10);
                m.send(loser, "duels.summary", "damage", fmt(damageDealt.getOrDefault(loser.getUniqueId(), 0.0)),
                        "opponent_damage", fmt(damageDealt.getOrDefault(winner.getUniqueId(), 0.0)));
                moveToSpectator(loser);
            }
            celebrate(winner);
        } else {
            for (Player p : onlineParticipants()) m.title(p, "draw", Map.of(), 5, 60, 10);
        }

        long delay = plugin.settings().duel().celebrationSeconds() * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, this::finish, Math.max(1L, delay));
    }

    private void moveToSpectator(Player loser) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!loser.isOnline() || phase == Phase.FINISHED) return;
            if (loser.isDead()) {
                loser.spigot().respawn();
                respawnTimes.put(loser.getUniqueId(), System.currentTimeMillis());
            }
            // The respawn event already places the loser at their arena spawn; only switch mode after it settled.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (loser.isOnline() && !loser.isDead() && phase == Phase.ENDING) loser.setGameMode(GameMode.SPECTATOR);
            }, RESPAWN_SETTLE_TICKS);
        });
    }

    private void celebrate(Player winner) {
        int seconds = plugin.settings().duel().celebrationSeconds();
        if (seconds <= 0) return;
        celebrationTask = new BukkitRunnable() {
            private int ticks = 0;

            @Override
            public void run() {
                if (phase != Phase.ENDING || !winner.isOnline() || ticks++ >= seconds) {
                    cancel();
                    return;
                }
                Location at = winner.getLocation().add(0, 1.0, 0);
                winner.getWorld().spawn(at, Firework.class, firework -> {
                    FireworkMeta meta = firework.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                            .withColor(Color.ORANGE, Color.YELLOW).withFade(Color.RED).flicker(true).build());
                    meta.setPower(1);
                    firework.setFireworkMeta(meta);
                });
                winner.playSound(winner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    /** Restores both players, unregisters the match and rolls the arena back. */
    private void finish() {
        if (phase == Phase.FINISHED) return;
        phase = Phase.FINISHED;
        cancelTasks();
        for (UUID id : participants()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restoreWhenAlive(p);
        }
        manager.unregister(this);
        arena.rollback().restoreBatched(plugin, plugin.settings().duel().rollbackBatchSize(),
                () -> arena.setState(ArenaState.WAITING));
    }

    /** Synchronous teardown for plugin shutdown: no scheduler calls. */
    void abort() {
        if (phase == Phase.FINISHED) return;
        phase = Phase.FINISHED;
        cancelTasks();
        for (UUID id : participants()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && !p.isDead()) restoreNow(p, false);
        }
        arena.rollback().restoreAll();
        arena.setState(ArenaState.WAITING);
    }

    private void restoreWhenAlive(Player player) {
        UUID id = player.getUniqueId();
        if (restored.contains(id)) return;
        if (player.isDead()) {
            player.spigot().respawn();
            respawnTimes.put(id, System.currentTimeMillis());
        }
        long sinceRespawn = System.currentTimeMillis() - respawnTimes.getOrDefault(id, 0L);
        long waitTicks = Math.max(0L, RESPAWN_SETTLE_TICKS - sinceRespawn / 50L);
        if (waitTicks <= 0L) {
            restoreNow(player, true);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !player.isDead()) restoreNow(player, true);
        }, waitTicks);
    }

    private void restoreNow(Player player, boolean async) {
        UUID id = player.getUniqueId();
        if (!restored.add(id)) return;
        PlayerSnapshot snapshot = snapshots.get(id);
        if (snapshot == null) return;
        plugin.cooldowns().clear(player);
        snapshot.restore(player, destinationFor(snapshot), async);
        plugin.snapshots().delete(id);
        manager.release(id);
    }

    private @Nullable Location destinationFor(PlayerSnapshot snapshot) {
        Settings.DuelSettings duel = plugin.settings().duel();
        if (duel.returnMode() == Settings.ReturnMode.LOBBY) {
            StoredLocation lobby = duel.lobby();
            if (lobby != null) {
                Location resolved = lobby.toLocation();
                if (resolved != null) return resolved;
            }
        }
        return snapshot.location();
    }

    private void cancelTasks() {
        for (BukkitTask task : new BukkitTask[]{countdownTask, tickTask, celebrationTask}) {
            if (task != null) task.cancel();
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<Player> onlineParticipants() {
        List<Player> list = new ArrayList<>(2);
        for (UUID id : participants()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) list.add(p);
        }
        return list;
    }

    private Map<String, String> placeholders() {
        Player p1 = Bukkit.getPlayer(id1);
        Player p2 = Bukkit.getPlayer(id2);
        return Messages.ph("kit", kit.displayName(), "arena", arena.displayName(), "time", TimeUtil.mmss(elapsedSeconds),
                "mode", plugin.messages().string("modes." + mode.name().toLowerCase(Locale.ROOT)),
                "player1", name1, "health1", p1 == null ? "0" : hearts(p1),
                "player2", name2, "health2", p2 == null ? "0" : hearts(p2));
    }

    private static String hearts(Player player) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0.0, player.getHealth()) / 2.0);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value / 2.0) + "\u2764";
    }
}
