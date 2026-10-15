package org.chiterok.grandDuels.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.config.Settings;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.utils.TimeUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ranked / unranked matchmaking: one FIFO queue per (mode, standard kit). A player waits in at most one queue.
 * Pairs are started with the server default rules; if no arena is free the players keep waiting and are retried
 * every second. Main thread only.
 */
public final class QueueManager {

    public enum JoinResult { QUEUED, SWITCHED, ALREADY_QUEUED, BUSY, NOT_STANDARD_KIT, KIT_DISABLED }

    public record Key(DuelMode mode, String kitId) {}

    private final ClaudeDuels plugin;
    private final Map<Key, LinkedHashMap<UUID, Long>> queues = new LinkedHashMap<>();
    private final Map<UUID, Key> byPlayer = new HashMap<>();
    private BukkitTask task;

    public QueueManager(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        queues.clear();
        byPlayer.clear();
    }

    public JoinResult join(Player player, DuelMode mode, Kit kit) {
        if (mode == DuelMode.FRIENDLY || kit.owner() != null) return JoinResult.NOT_STANDARD_KIT;
        if (!kit.isEnabledFor(mode)) return JoinResult.KIT_DISABLED;
        UUID id = player.getUniqueId();
        if (plugin.isOccupied(id)) return JoinResult.BUSY;

        Key key = new Key(mode, kit.id());
        Key old = byPlayer.get(id);
        if (key.equals(old)) return JoinResult.ALREADY_QUEUED;
        if (old != null) leave(id);

        queues.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(id, System.currentTimeMillis());
        byPlayer.put(id, key);
        tryPair(key);
        return old != null ? JoinResult.SWITCHED : JoinResult.QUEUED;
    }

    public boolean leave(UUID id) {
        Key key = byPlayer.remove(id);
        if (key == null) return false;
        LinkedHashMap<UUID, Long> queue = queues.get(key);
        if (queue != null) {
            queue.remove(id);
            if (queue.isEmpty()) queues.remove(key);
        }
        return true;
    }

    public @Nullable Key queueOf(UUID id) {
        return byPlayer.get(id);
    }

    /** Waiting players per kit id for one mode. */
    public Map<String, Integer> waitingPerKit(DuelMode mode) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<Key, LinkedHashMap<UUID, Long>> entry : queues.entrySet()) {
            if (entry.getKey().mode() == mode) counts.merge(entry.getKey().kitId(), entry.getValue().size(), Integer::sum);
        }
        return counts;
    }

    public int waitingInMode(DuelMode mode) {
        int total = 0;
        for (Map.Entry<Key, LinkedHashMap<UUID, Long>> entry : queues.entrySet()) {
            if (entry.getKey().mode() == mode) total += entry.getValue().size();
        }
        return total;
    }

    /** The kit was disabled (or removed) in kits.yml while players were waiting for it: they leave the queue. */
    private void dropQueue(Key key) {
        LinkedHashMap<UUID, Long> queue = queues.remove(key);
        if (queue == null) return;
        for (UUID id : queue.keySet()) {
            byPlayer.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player != null) plugin.messages().send(player, "queue.kit-disabled");
        }
    }

    private record Waiting(Player player, long joinedAt, int elo) {}

    private void tick() {
        for (Key key : new ArrayList<>(queues.keySet())) {
            Kit kit = plugin.kits().get(key.kitId());
            if (kit == null || !kit.isEnabledFor(key.mode())) {
                dropQueue(key);
                continue;
            }
            tryPair(key);
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Key> entry : new ArrayList<>(byPlayer.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            LinkedHashMap<UUID, Long> queue = queues.get(entry.getValue());
            if (player == null || queue == null) continue;
            Key key = entry.getValue();
            Kit kit = plugin.kits().get(key.kitId());
            long joinedAt = queue.getOrDefault(entry.getKey(), now);
            String modeName = plugin.messages().string("modes." + key.mode().name().toLowerCase(Locale.ROOT));
            String kitName = kit == null ? key.kitId() : kit.displayName();
            String time = TimeUtil.mmss((now - joinedAt) / 1000L);
            if (key.mode() == DuelMode.RANKED) {
                plugin.messages().actionBar(player, "queue.searching-ranked", "mode", modeName, "kit", kitName,
                        "time", time, "elo", ratingOf(player, key.kitId()), "range", eloRange(joinedAt, now));
            } else {
                plugin.messages().actionBar(player, "queue.searching", "mode", modeName, "kit", kitName, "time", time);
            }
        }
    }

    /** ELO used for matchmaking in a kit's queue: the kit's rating in kit rating mode, otherwise the global ELO. */
    public int ratingOf(Player player, String kitId) {
        if (plugin.settings().matchmaking().ratingMode() == Settings.RatingMode.KIT) {
            return plugin.kitRatings().elo(player.getUniqueId(), kitId);
        }
        return plugin.stats().cached(player.getUniqueId(), player.getName()).elo();
    }

    /** The rating shown when no kit is involved: global ELO, or the average over rated kits in kit rating mode. */
    public int overallRating(Player player) {
        if (plugin.settings().matchmaking().ratingMode() == Settings.RatingMode.KIT) {
            return plugin.kitRatings().overall(player.getUniqueId());
        }
        return plugin.stats().cached(player.getUniqueId(), player.getName()).elo();
    }

    /** Allowed ELO difference for a ranked player who joined the queue at {@code joinedAt}. */
    public int eloRange(long joinedAt, long now) {
        Settings.MatchmakingSettings cfg = plugin.settings().matchmaking();
        double range = cfg.initialEloRange() + cfg.eloRangePerSecond() * Math.max(0L, now - joinedAt) / 1000.0;
        if (cfg.maxEloRange() > 0) range = Math.min(range, cfg.maxEloRange());
        return (int) Math.round(range);
    }

    private void tryPair(Key key) {
        Kit kit = plugin.kits().get(key.kitId());
        if (kit == null) {
            // the kit was deleted while players were waiting
            LinkedHashMap<UUID, Long> queue = queues.get(key);
            if (queue != null) {
                for (UUID id : new ArrayList<>(queue.keySet())) {
                    leave(id);
                    Player player = Bukkit.getPlayer(id);
                    if (player != null) plugin.messages().send(player, "queue.kit-removed");
                }
            }
            return;
        }

        MatchSettings settings = MatchSettings.defaults(plugin.menus().defaults());
        while (true) {
            LinkedHashMap<UUID, Long> queue = queues.get(key);
            if (queue == null || queue.size() < 2) return;

            List<Waiting> waiting = collectWaiting(key, queue);
            if (waiting.size() < 2) return;

            Waiting[] pair = key.mode() == DuelMode.RANKED ? bestRankedPair(waiting)
                    : new Waiting[]{waiting.get(0), waiting.get(1)};
            if (pair == null) return; // nobody is within range yet; the ranges widen every second

            Player first = pair[0].player();
            Player second = pair[1].player();
            MatchManager.StartResult result = plugin.matches().start(first, second, kit, settings, key.mode());
            switch (result) {
                case STARTED -> notifyFound(pair[0], pair[1], key.mode());
                case NO_ARENA -> {
                    return; // everybody keeps waiting; retried on the next tick
                }
                case PLAYER_BUSY, FAILED -> {
                    leave(first.getUniqueId());
                    leave(second.getUniqueId());
                    plugin.messages().send(first, "duels.start-failed");
                    plugin.messages().send(second, "duels.start-failed");
                }
            }
        }
    }

    /** Snapshot of the valid waiting players (oldest first); players who left or got busy are dropped from the queue. */
    private List<Waiting> collectWaiting(Key key, LinkedHashMap<UUID, Long> queue) {
        List<Waiting> list = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : new LinkedHashMap<>(queue).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || plugin.isOccupied(entry.getKey())) {
                leave(entry.getKey());
                continue;
            }
            list.add(new Waiting(player, entry.getValue(), ratingOf(player, key.kitId())));
        }
        return list;
    }

    /**
     * ELO matchmaking: among all pairs whose rating difference fits the allowed range of at least one of the two
     * players (the range widens with waiting time), pick the pair with the smallest difference; ties go to the pair
     * that has waited longest. Returns {@code null} when nobody is in range yet.
     */
    private @Nullable Waiting[] bestRankedPair(List<Waiting> waiting) {
        long now = System.currentTimeMillis();
        Waiting bestA = null;
        Waiting bestB = null;
        int bestDiff = Integer.MAX_VALUE;
        long bestWait = -1L;
        for (int i = 0; i < waiting.size(); i++) {
            for (int j = i + 1; j < waiting.size(); j++) {
                Waiting a = waiting.get(i);
                Waiting b = waiting.get(j);
                int diff = Math.abs(a.elo() - b.elo());
                int allowed = Math.max(eloRange(a.joinedAt(), now), eloRange(b.joinedAt(), now));
                if (diff > allowed) continue;
                long waited = (now - a.joinedAt()) + (now - b.joinedAt());
                if (diff < bestDiff || (diff == bestDiff && waited > bestWait)) {
                    bestA = a;
                    bestB = b;
                    bestDiff = diff;
                    bestWait = waited;
                }
            }
        }
        return bestA == null ? null : new Waiting[]{bestA, bestB};
    }

    private void notifyFound(Waiting first, Waiting second, DuelMode mode) {
        for (Waiting self : List.of(first, second)) {
            Waiting other = self == first ? second : first;
            if (mode == DuelMode.RANKED) {
                plugin.messages().send(self.player(), "queue.found-ranked", "opponent", other.player().getName(),
                        "elo", other.elo(), "own_elo", self.elo());
            } else {
                plugin.messages().send(self.player(), "queue.found", "opponent", other.player().getName());
            }
        }
    }
}
