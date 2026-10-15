package org.chiterok.grandDuels.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.match.PlayerSnapshot;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Persists pre-duel snapshots to {@code snapshots/<uuid>.yml} while a duel runs, so that a crash or an
 * interrupted duel can never destroy a player's inventory. Files are tiny and written once per duel start.
 */
public final class SnapshotRepository {

    private final ClaudeDuels plugin;
    private final File directory;

    public SnapshotRepository(ClaudeDuels plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "snapshots");
    }

    public boolean save(UUID uuid, PlayerSnapshot snapshot) {
        if (!directory.exists() && !directory.mkdirs()) {
            plugin.getLogger().severe("Cannot create " + directory);
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        snapshot.writeTo(yaml);
        try {
            yaml.save(file(uuid));
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Cannot persist snapshot of " + uuid, e);
            return false;
        }
    }

    public @Nullable PlayerSnapshot load(UUID uuid) {
        File file = file(uuid);
        if (!file.isFile()) return null;
        try {
            return PlayerSnapshot.readFrom(YamlConfiguration.loadConfiguration(file));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Corrupt snapshot file " + file, e);
            return null;
        }
    }

    public void delete(UUID uuid) {
        File file = file(uuid);
        if (file.exists() && !file.delete()) plugin.getLogger().warning("Could not delete " + file);
    }

    private File file(UUID uuid) {
        return new File(directory, uuid + ".yml");
    }
}
