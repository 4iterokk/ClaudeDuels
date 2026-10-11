package org.chiterok.grandDuels.utils;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitTask;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.config.Settings;
import org.chiterok.grandDuels.data.PlayerStats;
import org.chiterok.grandDuels.data.RatingEntry;
import org.chiterok.grandDuels.data.StatsManager;
import org.chiterok.grandDuels.match.DuelMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PlaceholderAPI expansion {@code %grandduels_...%}. Only loaded when PlaceholderAPI is installed.
 * <pre>
 * %grandduels_in_ranked_count%        players in ranked duels right now
 * %grandduels_in_unranked_count%      players in unranked duels right now
 * %grandduels_elo%                    ELO of the player
 * %grandduels_elo_&lt;player&gt;%          ELO of the named player
 * %grandduels_elo_top_&lt;pos&gt;_name%    name of the player at position &lt;pos&gt; of the ELO leaderboard
 * %grandduels_elo_top_&lt;pos&gt;_count%   ELO of the player at position &lt;pos&gt;
 * %grandduels_kit_elo_&lt;kit&gt;%                    ELO of the player in the kit (kit rating mode only)
 * %grandduels_kit_elo_top_&lt;kit&gt;_&lt;pos&gt;_name%    name at position &lt;pos&gt; of the kit's leaderboard (kit rating mode only)
 * %grandduels_kit_elo_top_&lt;kit&gt;_&lt;pos&gt;_count%   ELO at position &lt;pos&gt; of the kit's leaderboard (kit rating mode only)
 * %grandduels_kd%                     K/D ratio of the player
 * %grandduels_kd_&lt;player&gt;%           K/D ratio of the named player
 * </pre>
 * The first four {@code elo} placeholders follow {@code matchmaking.rating-mode}: in {@code GLOBAL} mode they show the
 * global ELO (stats storage); in {@code KIT} mode they show the average over the kits a player is rated in, and the
 * leaderboard ranks by that average (kit rating storage). The {@code kit_elo} placeholders answer
 * "N/A" outside kit rating mode.
 * <p>
 * PlaceholderAPI may ask from any thread, so nothing here touches non-thread-safe state: duel counters are read live
 * on the main thread and from a copy refreshed every second otherwise; stats come from the thread-safe stats cache,
 * the cached leaderboard, or a short-lived copy that is loaded in the background for offline players.
 */
public final class PlaceholderUtil extends PlaceholderExpansion {

    private static final Pattern TOP = Pattern.compile("elo_top_(\\d{1,4})_(name|count)");
    private static final Pattern KIT_TOP = Pattern.compile("kit_elo_top_([a-z0-9_-]+)_(\\d{1,4})_(name|count)");
    private static final Pattern KIT_ELO = Pattern.compile("kit_elo_([a-z0-9_-]+)");
    private static final long COUNTER_REFRESH_TICKS = 20L;

    private final GrandDuels plugin;
    private volatile int rankedCount;
    private volatile int unrankedCount;
    private BukkitTask counterTask;

    public PlaceholderUtil(GrandDuels plugin) {
        this.plugin = plugin;
    }

    /** Registers the expansion and starts the counter refresh. Call on the main thread. */
    public boolean start() {
        refreshCounters();
        this.counterTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshCounters, COUNTER_REFRESH_TICKS,
                COUNTER_REFRESH_TICKS);
        return register();
    }

    public void stop() {
        if (counterTask != null) counterTask.cancel();
        unregister();
    }

    private void refreshCounters() {
        this.rankedCount = plugin.matches().playersInMode(DuelMode.RANKED);
        this.unrankedCount = plugin.matches().playersInMode(DuelMode.UNRANKED);
    }

    // ---------------------------------------------------------------- expansion metadata

    @Override
    public @NotNull String getIdentifier() {
        return "grandduels";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Stays registered when PlaceholderAPI reloads its expansions. */
    @Override
    public boolean persist() {
        return true;
    }

    // ---------------------------------------------------------------- requests

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        switch (key) {
            case "in_ranked_count":
                return String.valueOf(count(DuelMode.RANKED));
            case "in_unranked_count":
                return String.valueOf(count(DuelMode.UNRANKED));
            case "elo":
                return player == null ? unknown() : elo(player.getUniqueId());
            case "kd":
                return player == null ? unknown() : kd(player.getUniqueId());
            default:
                break;
        }

        Matcher top = TOP.matcher(key);
        if (top.matches()) return top(Integer.parseInt(top.group(1)), top.group(2).equals("name"));

        Matcher kitTop = KIT_TOP.matcher(key); // before KIT_ELO, which would also accept these
        if (kitTop.matches()) {
            return kitTop(kitTop.group(1), Integer.parseInt(kitTop.group(2)), kitTop.group(3).equals("name"));
        }
        Matcher kitElo = KIT_ELO.matcher(key);
        if (kitElo.matches()) return player == null ? unknown() : kitElo(player.getUniqueId(), kitElo.group(1));

        if (key.startsWith("elo_") && params.length() > 4) {
            UUID uuid = StatsManager.resolve(params.substring(4));
            return uuid == null ? unknown() : elo(uuid);
        }
        if (key.startsWith("kd_") && params.length() > 3) {
            UUID uuid = StatsManager.resolve(params.substring(3));
            return uuid == null ? unknown() : kd(uuid);
        }
        return null; // not ours: PlaceholderAPI leaves the text untouched
    }

    private int count(DuelMode mode) {
        if (Bukkit.isPrimaryThread()) return plugin.matches().playersInMode(mode);
        return mode == DuelMode.RANKED ? rankedCount : unrankedCount;
    }

    private boolean kitMode() {
        return plugin.settings().matchmaking().ratingMode() == Settings.RatingMode.KIT;
    }

    private String elo(UUID uuid) {
        if (kitMode()) {
            Map<String, Integer> ratings = plugin.kitRatings().peek(uuid);
            return ratings == null ? unknown() : String.valueOf(plugin.kitRatings().average(ratings));
        }
        PlayerStats stats = plugin.stats().peek(uuid);
        return stats == null ? unknown() : String.valueOf(stats.elo());
    }

    private String kd(UUID uuid) {
        PlayerStats stats = plugin.stats().peek(uuid);
        return stats == null ? unknown() : stats.kdFormatted();
    }

    private String kitElo(UUID uuid, String kitId) {
        if (!kitMode() || plugin.kits().get(kitId) == null) return unknown();
        Map<String, Integer> ratings = plugin.kitRatings().peek(uuid);
        if (ratings == null) return unknown();
        return String.valueOf(ratings.getOrDefault(kitId, PlayerStats.DEFAULT_ELO));
    }

    private String kitTop(String kitId, int position, boolean name) {
        if (!kitMode() || plugin.kits().get(kitId) == null) return unknown();
        return ratingEntry(plugin.kitRatings().top(kitId), position, name);
    }

    private String ratingEntry(List<RatingEntry> board, int position, boolean name) {
        if (position < 1 || position > board.size()) {
            return text(name ? "placeholders.empty-name" : "placeholders.empty-count");
        }
        RatingEntry entry = board.get(position - 1);
        return name ? entry.name() : String.valueOf(entry.elo());
    }

    private String top(int position, boolean name) {
        if (kitMode()) return ratingEntry(plugin.kitRatings().averageTop(), position, name);
        List<PlayerStats> board = plugin.stats().top();
        if (position < 1 || position > board.size()) {
            return text(name ? "placeholders.empty-name" : "placeholders.empty-count");
        }
        PlayerStats stats = board.get(position - 1);
        return name ? stats.name() : String.valueOf(stats.elo());
    }

    private String unknown() {
        return text("placeholders.unknown");
    }

    /** Fallback texts live in messages.yml (so they can be translated); colors are converted for PlaceholderAPI. */
    private String text(String path) {
        return LegacyComponentSerializer.legacySection().serialize(plugin.messages().get(path));
    }
}
