package org.chiterok.grandDuels.data;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.config.Settings;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Asynchronous facade of the per-kit ELO ratings ("kit rating mode", {@code matchmaking.rating-mode: KIT}).
 * The ratings live in their own storage ({@code kit-ratings.yml} / {@code kit-ratings.db}), separate from the global
 * stats. Like {@link StatsManager} all storage I/O runs on one dedicated thread, which serializes read-modify-write
 * cycles; the caches are read from the main thread and written only on the rating thread.
 */
public final class KitRatingManager {

    public static final int TOP_SIZE = 100;

    private static final long TOP_REFRESH_TICKS = 30L * 20L;
    private static final long OFFLINE_TTL_MILLIS = 30_000L;
    private static final int OFFLINE_CACHE_LIMIT = 256;

    private record Cached(Map<String, Integer> ratings, long loadedAt) {}

    private final GrandDuels plugin;
    private final ConcurrentHashMap<UUID, Map<String, Integer>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Cached> offlineCache = new ConcurrentHashMap<>();
    private final Set<UUID> offlineLoading = ConcurrentHashMap.newKeySet();
    private volatile Map<String, List<RatingEntry>> kitTops = Map.of();
    private volatile List<RatingEntry> averageTop = List.of();
    private ExecutorService executor;
    private KitRatingStorage storage;
    private BukkitTask topTask;

    public KitRatingManager(GrandDuels plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Settings.StorageType type = plugin.settings().storageType();
        KitRatingStorage candidate = type == Settings.StorageType.SQLITE
                ? new SqliteKitRatingStorage(plugin.getDataFolder()) : new YamlKitRatingStorage(plugin.getDataFolder());
        try {
            candidate.init();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Kit rating storage " + type + " failed, falling back to YAML", e);
            candidate.close();
            candidate = new YamlKitRatingStorage(plugin.getDataFolder());
            try {
                candidate.init();
            } catch (Exception fatal) {
                plugin.getLogger().log(Level.SEVERE, "YAML kit rating storage failed too; kit ratings are disabled", fatal);
                candidate = null;
            }
        }
        this.storage = candidate;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "GrandDuels-KitRatings");
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

    // ---------------------------------------------------------------- ratings

    /**
     * The overall rating of a player in kit rating mode: the sum of the ratings in ALL kits divided by the number of
     * kits. A kit the player has not played counts with the default rating.
     */
    public int average(Map<String, Integer> ratings) {
        return average(ratings, plugin.kits().ids());
    }

    public static int average(Map<String, Integer> ratings, Collection<String> kitIds) {
        if (kitIds.isEmpty()) return PlayerStats.DEFAULT_ELO;
        long sum = 0L;
        for (String kitId : kitIds) sum += ratings.getOrDefault(kitId, PlayerStats.DEFAULT_ELO);
        return (int) Math.round((double) sum / kitIds.size());
    }

    /** Cached rating of an online player in one kit (the default rating when unrated). Main thread. */
    public int elo(UUID uuid, String kitId) {
        Map<String, Integer> ratings = cache.get(uuid);
        return ratings == null ? PlayerStats.DEFAULT_ELO : ratings.getOrDefault(kitId, PlayerStats.DEFAULT_ELO);
    }

    /** Average rating over the kits an online player is rated in. Main thread. */
    public int overall(UUID uuid) {
        Map<String, Integer> ratings = cache.get(uuid);
        return ratings == null ? PlayerStats.DEFAULT_ELO : average(ratings);
    }

    public void preload(Player player) {
        UUID uuid = player.getUniqueId();
        if (executor == null || storage == null || executor.isShutdown()) return;
        executor.execute(() -> {
            try {
                cache.put(uuid, Map.copyOf(storage.load(uuid)));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load kit ratings for " + uuid, e);
            }
        });
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
     * Records a finished ranked duel in {@code kitId}: the winner gains what the loser loses (ELO).
     * @return completes on the rating thread with the points the winner gained (0 on error)
     */
    public CompletableFuture<Integer> recordResult(Player winner, Player loser, String kitId) {
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
                Map<String, Integer> winnerRatings = loadForUpdate(winnerId);
                Map<String, Integer> loserRatings = loadForUpdate(loserId);
                int winnerElo = winnerRatings.getOrDefault(kitId, PlayerStats.DEFAULT_ELO);
                int loserElo = loserRatings.getOrDefault(kitId, PlayerStats.DEFAULT_ELO);
                double expected = 1.0 / (1.0 + Math.pow(10.0, (loserElo - winnerElo) / 400.0));
                int gain = Math.max(1, (int) Math.round(kFactor * (1.0 - expected)));
                int newWinner = winnerElo + gain;
                int newLoser = Math.max(0, loserElo - gain);

                storage.save(winnerId, winnerName, kitId, newWinner);
                storage.save(loserId, loserName, kitId, newLoser);
                cache.put(winnerId, with(winnerRatings, kitId, newWinner));
                cache.put(loserId, with(loserRatings, kitId, newLoser));
                reloadTop();
                result.complete(gain);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not record kit rating result", e);
                result.complete(0);
            }
        });
        return result;
    }

    private Map<String, Integer> loadForUpdate(UUID uuid) throws Exception {
        Map<String, Integer> current = cache.get(uuid);
        return current != null ? current : storage.load(uuid);
    }

    private static Map<String, Integer> with(Map<String, Integer> ratings, String kitId, int elo) {
        Map<String, Integer> copy = new HashMap<>(ratings);
        copy.put(kitId, elo);
        return Map.copyOf(copy);
    }

    // ---------------------------------------------------------------- leaderboards

    /** Best players of one kit (at most {@link #TOP_SIZE}); refreshed every 30 seconds and after ranked duels. */
    public List<RatingEntry> top(String kitId) {
        return kitTops.getOrDefault(kitId.toLowerCase(java.util.Locale.ROOT), List.of());
    }

    /** Best players by average rating over their kits. */
    public List<RatingEntry> averageTop() {
        return averageTop;
    }

    /** Queues a leaderboard reload on the rating thread. Safe to call from any thread. */
    public void refreshTop() {
        if (executor == null || storage == null || executor.isShutdown()) return;
        executor.execute(this::reloadTop);
    }

    /** Rating thread only. Skipped while the plugin rates globally: nothing reads these boards then. */
    private void reloadTop() {
        if (plugin.settings().matchmaking().ratingMode() != Settings.RatingMode.KIT) return;
        try {
            Map<String, List<RatingEntry>> boards = new HashMap<>();
            for (String kitId : plugin.kits().ids()) boards.put(kitId, List.copyOf(storage.top(kitId, TOP_SIZE)));
            this.kitTops = Map.copyOf(boards);
            this.averageTop = List.copyOf(storage.topAverage(plugin.kits().ids(), TOP_SIZE));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not load the kit rating leaderboards", e);
        }
    }

    // ---------------------------------------------------------------- placeholder and command support

    /**
     * Non-blocking read for placeholders: the live cache for online players; for everybody else the last loaded copy
     * (a background load starts when it is missing or older than 30 seconds).
     * @return {@code null} until the first load of an offline player has finished
     */
    public @Nullable Map<String, Integer> peek(UUID uuid) {
        Map<String, Integer> online = cache.get(uuid);
        if (online != null) return online;
        Cached cached = offlineCache.get(uuid);
        if (cached == null || System.currentTimeMillis() - cached.loadedAt() > OFFLINE_TTL_MILLIS) loadOffline(uuid);
        return cached == null ? null : cached.ratings();
    }

    private void loadOffline(UUID uuid) {
        if (!offlineLoading.add(uuid)) return;
        lookup(uuid).whenComplete((ratings, error) -> {
            try {
                if (error == null) {
                    if (offlineCache.size() >= OFFLINE_CACHE_LIMIT) offlineCache.clear();
                    offlineCache.put(uuid, new Cached(ratings, System.currentTimeMillis()));
                }
            } finally {
                offlineLoading.remove(uuid);
            }
        });
    }

    /** Looks up the kit ratings of any player (online or not) without blocking the main thread. */
    public CompletableFuture<Map<String, Integer>> lookup(UUID uuid) {
        Map<String, Integer> hit = cache.get(uuid);
        if (hit != null) return CompletableFuture.completedFuture(hit);
        if (executor == null || storage == null || executor.isShutdown()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Map.copyOf(storage.load(uuid));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load kit ratings for " + uuid, e);
                return Map.<String, Integer>of();
            }
        }, executor);
    }
}
