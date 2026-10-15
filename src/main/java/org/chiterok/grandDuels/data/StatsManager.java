package org.chiterok.grandDuels.data;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.config.Settings;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.scheduler.BukkitTask;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

/**
 * Asynchronous stats facade. All storage I/O runs on one dedicated thread, which serializes
 * read-modify-write cycles (no lost updates). The cache is read from the main thread and written only on that thread.
 */
public final class StatsManager {

    /** How many players the cached leaderboard ({@link #top()}) holds. */
    public static final int TOP_SIZE = 100;

    private static final long TOP_REFRESH_TICKS = 30L * 20L;
    private static final long OFFLINE_TTL_MILLIS = 30_000L;
    private static final int OFFLINE_CACHE_LIMIT = 256;

    private record Cached(PlayerStats stats, long loadedAt) {}

    private final ClaudeDuels plugin;
    private final ConcurrentHashMap<UUID, PlayerStats> cache = new ConcurrentHashMap<>();
    /** Stats of players who are not online, loaded on demand for placeholders (short lived). */
    private final ConcurrentHashMap<UUID, Cached> offlineCache = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> offlineLoading = ConcurrentHashMap.newKeySet();
    private volatile List<PlayerStats> top = List.of();
    private ExecutorService executor;
    private StatsStorage storage;
    private BukkitTask topTask;

    public StatsManager(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Settings.StorageType type = plugin.settings().storageType();
        StatsStorage candidate = type == Settings.StorageType.SQLITE
                ? new SqliteStatsStorage(plugin.getDataFolder()) : new YamlStatsStorage(plugin.getDataFolder());
        try {
            candidate.init();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Stats storage " + type + " failed, falling back to YAML", e);
            candidate.close();
            candidate = new YamlStatsStorage(plugin.getDataFolder());
            try {
                candidate.init();
            } catch (Exception fatal) {
                plugin.getLogger().log(Level.SEVERE, "YAML stats storage failed too; stats are disabled", fatal);
                candidate = null;
            }
        }
        this.storage = candidate;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GrandDuels-Stats");
            t.setDaemon(true);
            return t;
        });
        refreshTop();
        this.topTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshTop, TOP_REFRESH_TICKS, TOP_REFRESH_TICKS);
    }

    public void stop() {
        if (topTask != null) topTask.cancel();
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (storage != null) storage.close();
    }

    /** Cached stats or an empty record (never null). Main thread. */
    public PlayerStats cached(UUID uuid, String name) {
        PlayerStats stats = cache.get(uuid);
        return stats != null ? stats : PlayerStats.empty(uuid, name);
    }

    public void preload(Player player) {
        update(player.getUniqueId(), player.getName(), s -> s);
    }

    /** Queued behind pending updates so a just-recorded result is not re-cached for an offline player. */
    public void evict(UUID uuid) {
        if (executor == null || executor.isShutdown()) {
            cache.remove(uuid);
            return;
        }
        executor.execute(() -> cache.remove(uuid));
    }

    /**
     * Records a finished duel. Ranked duels also move the ELO rating (the loser loses what the winner gains).
     * @return completes on the storage thread with the rating points the winner gained (0 when unranked or on error)
     */
    public CompletableFuture<Integer> recordResult(Player winner, Player loser, boolean ranked) {
        CompletableFuture<Integer> result = new CompletableFuture<>();
        if (executor == null || storage == null || executor.isShutdown()) {
            result.complete(0);
            return result;
        }
        final UUID winnerId = winner.getUniqueId();
        final UUID loserId = loser.getUniqueId();
        final String winnerName = winner.getName();
        final String loserName = loser.getName();
        final int kFactor = plugin.settings().matchmaking().kFactor();
        executor.execute(() -> {
            try {
                PlayerStats w = loadForUpdate(winnerId, winnerName);
                PlayerStats l = loadForUpdate(loserId, loserName);
                PlayerStats w2 = w.afterWin();
                PlayerStats l2 = l.afterLoss();
                int gain = 0;
                if (ranked) {
                    double expected = 1.0 / (1.0 + Math.pow(10.0, (l.elo() - w.elo()) / 400.0));
                    gain = Math.max(1, (int) Math.round(kFactor * (1.0 - expected)));
                    w2 = w2.withElo(w.elo() + gain);
                    l2 = l2.withElo(l.elo() - gain);
                }
                storage.save(w2);
                storage.save(l2);
                cache.put(winnerId, w2);
                cache.put(loserId, l2);
                if (ranked) reloadTop();
                result.complete(gain);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not record duel result", e);
                result.complete(0);
            }
        });
        return result;
    }

    private PlayerStats loadForUpdate(UUID uuid, String name) throws Exception {
        PlayerStats current = cache.get(uuid);
        if (current == null) current = storage.load(uuid).orElseGet(() -> PlayerStats.empty(uuid, name));
        return current.withName(name);
    }

    // ---------------------------------------------------------------- leaderboard and placeholder support

    /** Players with the highest ELO, best first (at most {@link #TOP_SIZE}); refreshed every 30 seconds and after ranked duels. */
    public List<PlayerStats> top() {
        return top;
    }

    /** Queues a leaderboard reload on the storage thread. Safe to call from any thread. */
    public void refreshTop() {
        if (executor == null || storage == null || executor.isShutdown()) return;
        executor.execute(this::reloadTop);
    }

    /** Storage thread only. */
    private void reloadTop() {
        try {
            this.top = List.copyOf(storage.top(TOP_SIZE));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not load the ELO leaderboard", e);
        }
    }

    /**
     * Non-blocking stats read for placeholders: the live cache for online players; for everybody else the last loaded
     * copy (a background load is started when it is missing or older than 30 seconds).
     * @return {@code null} until the first load of an offline player has finished
     */
    public @Nullable PlayerStats peek(UUID uuid) {
        PlayerStats online = cache.get(uuid);
        if (online != null) return online;
        Cached cached = offlineCache.get(uuid);
        if (cached == null || System.currentTimeMillis() - cached.loadedAt() > OFFLINE_TTL_MILLIS) loadOffline(uuid);
        return cached == null ? null : cached.stats();
    }

    private void loadOffline(UUID uuid) {
        if (!offlineLoading.add(uuid)) return;
        lookup(uuid).whenComplete((result, error) -> {
            try {
                if (error == null) {
                    if (offlineCache.size() >= OFFLINE_CACHE_LIMIT) offlineCache.clear();
                    offlineCache.put(uuid, new Cached(result.orElseGet(() -> PlayerStats.empty(uuid, "")),
                            System.currentTimeMillis()));
                }
            } finally {
                offlineLoading.remove(uuid);
            }
        });
    }

    /** Looks up stats of any player (online or not) without blocking the main thread. */
    public CompletableFuture<Optional<PlayerStats>> lookup(UUID uuid) {
        PlayerStats hit = cache.get(uuid);
        if (hit != null) return CompletableFuture.completedFuture(Optional.of(hit));
        if (executor == null || storage == null) return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.supplyAsync(() -> {
            try {
                return storage.load(uuid);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load stats for " + uuid, e);
                return Optional.<PlayerStats>empty();
            }
        }, executor);
    }

    private void update(UUID uuid, String name, UnaryOperator<PlayerStats> change) {
        if (executor == null || storage == null || executor.isShutdown()) return;
        executor.execute(() -> {
            try {
                PlayerStats current = cache.get(uuid);
                if (current == null) {
                    current = storage.load(uuid).orElseGet(() -> PlayerStats.empty(uuid, name));
                }
                PlayerStats updated = change.apply(current.withName(name));
                storage.save(updated);
                cache.put(uuid, updated);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not update stats for " + uuid, e);
            }
        });
    }

    /** Resolves a name to a UUID using only locally cached data (no blocking Mojang lookup). */
    public static @Nullable UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        var cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : cached.getUniqueId();
    }
}
