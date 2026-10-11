package org.chiterok.grandDuels.data;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Blocking storage of the per-kit ELO ratings (kept apart from the global stats, see {@link StatsStorage}).
 * Implementations are only called from the single rating thread.
 */
public interface KitRatingStorage extends AutoCloseable {

    void init() throws Exception;

    /** Kit id -> ELO of every kit the player has a rating for (empty when none). */
    Map<String, Integer> load(UUID uuid) throws Exception;

    void save(UUID uuid, String name, String kitId, int elo) throws Exception;

    /** The {@code limit} best players of one kit, best first. */
    List<RatingEntry> top(String kitId, int limit) throws Exception;

    /**
     * The {@code limit} players with the highest average over {@code kitIds} (a kit without a rating counts with the
     * default rating), best first. Only players with at least one rating are listed.
     */
    List<RatingEntry> topAverage(Collection<String> kitIds, int limit) throws Exception;

    @Override
    void close();
}
