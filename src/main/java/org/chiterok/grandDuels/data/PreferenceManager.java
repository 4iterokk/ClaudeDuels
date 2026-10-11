package org.chiterok.grandDuels.data;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.cooldown.RuleType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Per-player duel preferences, persisted in {@code preferences.yml}. Reads/writes of the in-memory map happen on the
 * main thread; the file is written asynchronously from a serialized snapshot (newest snapshot wins).
 */
public final class PreferenceManager {

    private final GrandDuels plugin;
    private final File file;
    private final Map<UUID, PlayerPreferences> preferences = new HashMap<>();
    private final Object ioLock = new Object();
    private boolean dirty;
    private long version;
    private long lastWritten;

    public PreferenceManager(GrandDuels plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "preferences.yml");
    }

    public void load() {
        preferences.clear();
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection s = players.getConfigurationSection(key);
            if (s == null) continue;
            PlayerPreferences prefs = new PlayerPreferences(this::markDirty);
            prefs.loadCustomCooldowns(s.getBoolean("custom-cooldowns", true));
            ConfigurationSection cooldowns = s.getConfigurationSection("cooldowns");
            ConfigurationSection bans = s.getConfigurationSection("bans");
            List<String> legacyBanned = s.getStringList("banned");
            for (RuleType type : RuleType.values()) {
                String ruleKey = type.configKey();
                if (cooldowns != null && cooldowns.contains(ruleKey)) {
                    prefs.cooldownOverrides().put(type, Math.max(0.0, cooldowns.getDouble(ruleKey)));
                }
                if (bans != null && bans.contains(ruleKey)) prefs.banOverrides().put(type, bans.getBoolean(ruleKey));
                else if (legacyBanned.contains(ruleKey)) prefs.banOverrides().put(type, true);
            }
            preferences.put(uuid, prefs);
        }
    }

    public PlayerPreferences get(UUID uuid) {
        return preferences.computeIfAbsent(uuid, id -> new PlayerPreferences(this::markDirty));
    }

    private void markDirty() {
        dirty = true;
    }

    /** Writes the file asynchronously if anything changed. */
    public void save() {
        if (!dirty) return;
        dirty = false;
        final long snapshotVersion = ++version;
        final String data = serialize();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(snapshotVersion, data));
    }

    /** Synchronous save for plugin shutdown. */
    public void saveNow() {
        if (!dirty && version > 0) return;
        dirty = false;
        write(++version, serialize());
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerPreferences> entry : preferences.entrySet()) {
            PlayerPreferences prefs = entry.getValue();
            if (prefs.isDefault()) continue;
            String base = "players." + entry.getKey();
            yaml.set(base + ".custom-cooldowns", prefs.customCooldowns());
            for (Map.Entry<RuleType, Double> o : prefs.cooldownOverrides().entrySet()) {
                yaml.set(base + ".cooldowns." + o.getKey().configKey(), o.getValue());
            }
            for (Map.Entry<RuleType, Boolean> o : prefs.banOverrides().entrySet()) {
                yaml.set(base + ".bans." + o.getKey().configKey(), o.getValue());
            }
        }
        return yaml.saveToString();
    }

    private void write(long snapshotVersion, String data) {
        synchronized (ioLock) {
            if (snapshotVersion < lastWritten) return;
            lastWritten = snapshotVersion;
            try {
                File parent = file.getParentFile();
                if (parent != null) Files.createDirectories(parent.toPath());
                Files.writeString(file.toPath(), data, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save " + file, e);
            }
        }
    }
}
