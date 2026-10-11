package org.chiterok.grandDuels.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code kit-ratings.yml}: {@code players.<uuid>.name} and {@code players.<uuid>.kits.<kit id>: <elo>}. */
public final class YamlKitRatingStorage implements KitRatingStorage {

    private static final Comparator<RatingEntry> BEST_FIRST = Comparator.comparingInt(RatingEntry::elo).reversed()
            .thenComparing(RatingEntry::name, String.CASE_INSENSITIVE_ORDER);

    private final File file;
    private YamlConfiguration yaml;

    public YamlKitRatingStorage(File dataFolder) {
        this.file = new File(dataFolder, "kit-ratings.yml");
    }

    @Override
    public synchronized void init() throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    @Override
    public synchronized Map<String, Integer> load(UUID uuid) {
        Map<String, Integer> ratings = new LinkedHashMap<>();
        ConfigurationSection kits = yaml.getConfigurationSection("players." + uuid + ".kits");
        if (kits != null) {
            for (String kit : kits.getKeys(false)) ratings.put(kit, kits.getInt(kit, PlayerStats.DEFAULT_ELO));
        }
        return ratings;
    }

    @Override
    public synchronized void save(UUID uuid, String name, String kitId, int elo) throws IOException {
        String base = "players." + uuid;
        yaml.set(base + ".name", name);
        yaml.set(base + ".kits." + kitId, elo);
        yaml.save(file);
    }

    @Override
    public synchronized List<RatingEntry> top(String kitId, int limit) {
        List<RatingEntry> all = new ArrayList<>();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                ConfigurationSection kits = players.getConfigurationSection(key + ".kits");
                UUID uuid = parse(key);
                if (uuid == null || kits == null || !kits.isSet(kitId)) continue;
                all.add(new RatingEntry(uuid, players.getString(key + ".name", "unknown"), kits.getInt(kitId)));
            }
        }
        return limited(all, limit);
    }

    @Override
    public synchronized List<RatingEntry> topAverage(Collection<String> kitIds, int limit) {
        List<RatingEntry> all = new ArrayList<>();
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID uuid = parse(key);
                if (uuid == null) continue;
                Map<String, Integer> ratings = load(uuid);
                if (ratings.isEmpty()) continue;
                all.add(new RatingEntry(uuid, players.getString(key + ".name", "unknown"),
                        KitRatingManager.average(ratings, kitIds)));
            }
        }
        return limited(all, limit);
    }

    private static List<RatingEntry> limited(List<RatingEntry> all, int limit) {
        all.sort(BEST_FIRST);
        return new ArrayList<>(all.subList(0, Math.min(Math.max(1, limit), all.size())));
    }

    private static UUID parse(String key) {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void close() {
        // nothing to release; every save is flushed immediately
    }
}
