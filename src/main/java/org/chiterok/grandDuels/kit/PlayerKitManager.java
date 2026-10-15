package org.chiterok.grandDuels.kit;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.config.Settings;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Player-made kits, saved from the player's own inventory (so players can only use items they actually own) into
 * {@code playerkits/<uuid>.yml}. Files are tiny, so reads and writes are synchronous. Main thread only.
 */
public final class PlayerKitManager {

    public enum SaveResult { CREATED, UPDATED, EXISTS, LIMIT_REACHED, EMPTY, DISABLED, FAILED }

    public record SaveOutcome(SaveResult result, int skippedItems) {}

    private final ClaudeDuels plugin;
    private final File directory;
    private final Map<UUID, Map<String, Kit>> cache = new HashMap<>();

    public PlayerKitManager(ClaudeDuels plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "playerkits");
    }

    public List<Kit> list(UUID owner) {
        return new ArrayList<>(load(owner).values());
    }

    public @Nullable Kit get(UUID owner, String name) {
        return load(owner).get(name.toLowerCase(Locale.ROOT));
    }

    public int limit(Player player) {
        if (player.hasPermission("grandduels.kits.unlimited")) return Integer.MAX_VALUE;
        return plugin.settings().playerKits().maxPerPlayer();
    }

    /**
     * Checks whether the player may start building kit {@code name}.
     * @param mustExist true when editing an existing kit, false when creating a new one
     * @return {@code null} when everything is fine, otherwise why not (EXISTS, LIMIT_REACHED, DISABLED, EMPTY=not found)
     */
    public @Nullable SaveResult precheck(Player player, String name, boolean mustExist) {
        int limit = limit(player);
        if (limit <= 0) return SaveResult.DISABLED;
        Map<String, Kit> kits = load(player.getUniqueId());
        boolean exists = kits.containsKey(name.toLowerCase(Locale.ROOT));
        if (mustExist) return exists ? null : SaveResult.EMPTY;
        if (exists) return SaveResult.EXISTS;
        return kits.size() >= limit ? SaveResult.LIMIT_REACHED : null;
    }

    /** Saves the player's current inventory as kit {@code name}. {@code overwrite=false} refuses to replace one. */
    public SaveOutcome save(Player player, String name, boolean overwrite) {
        Set<Material> banned = plugin.settings().playerKits().bannedMaterials();
        return save(player, name, overwrite, item -> banned.contains(item.getType()));
    }

    /**
     * Saves the creative editor's loadout. Every item is validated by {@link PlayerKitItemFilter}: a creative player
     * (or a modified client) can create items that no survival player could own.
     */
    public SaveOutcome saveEdited(Player player, String name, boolean overwrite) {
        Settings.PlayerKitSettings rules = plugin.settings().playerKits();
        return save(player, name, overwrite, new PlayerKitItemFilter(rules.bannedMaterials(), rules.editor().maxItemBytes()));
    }

    private SaveOutcome save(Player player, String name, boolean overwrite, Predicate<ItemStack> rejected) {
        String id = name.toLowerCase(Locale.ROOT);
        int limit = limit(player);
        if (limit <= 0) return new SaveOutcome(SaveResult.DISABLED, 0);

        Map<String, Kit> kits = load(player.getUniqueId());
        boolean exists = kits.containsKey(id);
        if (exists && !overwrite) return new SaveOutcome(SaveResult.EXISTS, 0);
        if (!exists && kits.size() >= limit) return new SaveOutcome(SaveResult.LIMIT_REACHED, 0);

        File file = file(player.getUniqueId());
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.createSection("kits." + id);
        ItemStack inHand = player.getInventory().getItemInMainHand();
        section.set("display-name", "&f" + id + " &8(custom)");
        section.set("icon", inHand.getType().isAir() ? Material.IRON_SWORD.name() : inHand.getType().name());
        section.set("description", List.of("&7Custom kit by &f" + player.getName()));
        int skipped = plugin.kits().writeLoadout(section, player, false, rejected);

        Kit parsed;
        try {
            parsed = plugin.kits().parse(id, section, player.getUniqueId());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Could not build player kit " + id + " for " + player.getName(), e);
            return new SaveOutcome(SaveResult.FAILED, skipped);
        }
        if (parsed.items().isEmpty() && parsed.helmet() == null && parsed.chestplate() == null
                && parsed.leggings() == null && parsed.boots() == null && parsed.offhand() == null) {
            return new SaveOutcome(SaveResult.EMPTY, skipped);
        }

        yaml.set("owner-name", player.getName());
        if (!write(yaml, file)) return new SaveOutcome(SaveResult.FAILED, skipped);
        kits.put(id, parsed);
        return new SaveOutcome(exists ? SaveResult.UPDATED : SaveResult.CREATED, skipped);
    }

    public boolean delete(UUID owner, String name) {
        String id = name.toLowerCase(Locale.ROOT);
        Map<String, Kit> kits = load(owner);
        if (!kits.containsKey(id)) return false;
        File file = file(owner);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("kits." + id, null);
        if (!write(yaml, file)) return false;
        kits.remove(id);
        return true;
    }

    /** Drops cached kits of a player who left (they are re-read from disk on demand). */
    public void evict(UUID owner) {
        cache.remove(owner);
    }

    private Map<String, Kit> load(UUID owner) {
        Map<String, Kit> cached = cache.get(owner);
        if (cached != null) return cached;

        Map<String, Kit> kits = new LinkedHashMap<>();
        File file = file(owner);
        if (file.isFile()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = yaml.getConfigurationSection("kits");
            if (root != null) {
                for (String key : root.getKeys(false)) {
                    ConfigurationSection section = root.getConfigurationSection(key);
                    if (section == null) continue;
                    try {
                        kits.put(key.toLowerCase(Locale.ROOT), plugin.kits().parse(key.toLowerCase(Locale.ROOT), section, owner));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Skipping player kit '" + key + "' of " + owner + ": " + e.getMessage());
                    }
                }
            }
        }
        cache.put(owner, kits);
        return kits;
    }

    private boolean write(YamlConfiguration yaml, File file) {
        try {
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("cannot create " + directory);
            yaml.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + file, e);
            return false;
        }
    }

    private File file(UUID owner) {
        return new File(directory, owner + ".yml");
    }
}
