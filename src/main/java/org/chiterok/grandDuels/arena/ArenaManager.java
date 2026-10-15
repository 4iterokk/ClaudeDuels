package org.chiterok.grandDuels.arena;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.BoundingBox;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.utils.StoredLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Registry and persistence (arenas.yml) of arenas, including which arenas each standard kit may be played on.
 * A kit without an entry in {@code kit-arenas} can be played on every arena. Mutations happen on the main thread.
 */
public final class ArenaManager {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final ClaudeDuels plugin;
    private final Map<String, Arena> arenas = new ConcurrentHashMap<>();
    /** Kit id -> ids of the arenas the kit may use. Absent = unrestricted. */
    private final Map<String, Set<String>> kitArenas = new ConcurrentHashMap<>();

    public ArenaManager(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    public static boolean isValidName(String name) {
        return VALID_NAME.matcher(name).matches();
    }

    public void load() {
        arenas.clear();
        kitArenas.clear();
        YamlConfiguration yaml = plugin.configs().arenasFile().get();
        ConfigurationSection root = yaml.getConfigurationSection("arenas");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) continue;
                Arena arena = new Arena(key.toLowerCase(Locale.ROOT));
                arena.setPos1(StoredLocation.read(section.getConfigurationSection("pos1")));
                arena.setPos2(StoredLocation.read(section.getConfigurationSection("pos2")));
                arena.setDisplayName(section.getString("display-name"));
                arena.setDisplayMaterial(parseIcon(section.getString("icon")));
                arenas.put(arena.name(), arena);
            }
        }
        ConfigurationSection restrictions = yaml.getConfigurationSection("kit-arenas");
        if (restrictions != null) {
            for (String kitId : restrictions.getKeys(false)) {
                Set<String> allowed = new LinkedHashSet<>();
                for (String name : restrictions.getStringList(kitId)) allowed.add(name.toLowerCase(Locale.ROOT));
                allowed.retainAll(arenas.keySet());
                if (!allowed.isEmpty()) kitArenas.put(kitId.toLowerCase(Locale.ROOT), allowed);
            }
        }
        plugin.getLogger().info("Loaded " + arenas.size() + " arena(s).");
    }

    public void save() {
        YamlConfiguration yaml = plugin.configs().arenasFile().get();
        yaml.set("arenas", null);
        for (Arena arena : arenas.values()) {
            ConfigurationSection section = yaml.createSection("arenas." + arena.name());
            StoredLocation p1 = arena.pos1();
            StoredLocation p2 = arena.pos2();
            if (p1 != null) p1.write(section.createSection("pos1"));
            if (p2 != null) p2.write(section.createSection("pos2"));
            if (arena.hasCustomName()) section.set("display-name", arena.displayName());
            if (arena.displayMaterial() != null) section.set("icon", arena.displayMaterial().name());
        }
        yaml.set("kit-arenas", null);
        for (Map.Entry<String, Set<String>> entry : kitArenas.entrySet()) {
            yaml.set("kit-arenas." + entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        plugin.configs().arenasFile().save();
    }

    public @Nullable Arena get(String name) {
        return arenas.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Arena> all() {
        return List.copyOf(arenas.values());
    }

    public List<String> names() {
        return new ArrayList<>(arenas.keySet());
    }

    /** @return the new arena, or {@code null} if one with that name exists. */
    public @Nullable Arena create(String name) {
        Arena arena = new Arena(name.toLowerCase(Locale.ROOT));
        if (arenas.putIfAbsent(arena.name(), arena) != null) return null;
        save();
        return arena;
    }

    /** @return false if the arena does not exist or is in use. */
    public boolean delete(String name) {
        Arena arena = get(name);
        if (arena == null || arena.state() != ArenaState.WAITING) return false;
        arenas.remove(arena.name());
        for (Map.Entry<String, Set<String>> entry : List.copyOf(kitArenas.entrySet())) {
            Set<String> remaining = new LinkedHashSet<>(entry.getValue());
            remaining.remove(arena.name());
            // a kit whose only arena was deleted becomes unrestricted again instead of unplayable
            if (remaining.isEmpty()) kitArenas.remove(entry.getKey());
            else kitArenas.put(entry.getKey(), remaining);
        }
        save();
        return true;
    }

    /** Sets (or, with null/blank, clears) the custom name of an arena. @return false if it does not exist. */
    public boolean setDisplayName(String name, @Nullable String displayName) {
        Arena arena = get(name);
        if (arena == null) return false;
        arena.setDisplayName(displayName);
        save();
        return true;
    }

    /** Sets (or, with null, clears) the arena selector icon of an arena. @return false if it does not exist. */
    public boolean setDisplayMaterial(String name, @Nullable Material material) {
        Arena arena = get(name);
        if (arena == null) return false;
        arena.setDisplayMaterial(material);
        save();
        return true;
    }

    /** A usable menu icon: an existing, non-air item material; {@code null} for anything else. */
    public static @Nullable Material parseIcon(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        Material material = Material.matchMaterial(raw.trim());
        return material == null || material.isAir() || !material.isItem() ? null : material;
    }

    // ---------------------------------------------------------------- arenas per kit

    /** True when an admin picked a list of arenas for the standard kit. */
    public boolean isRestricted(String kitId) {
        return kitArenas.containsKey(kitId.toLowerCase(Locale.ROOT));
    }

    /** Player kits may use every arena; standard kits follow the admin's list (unrestricted when none). */
    public boolean isAllowed(Kit kit, Arena arena) {
        if (kit.owner() != null) return true;
        Set<String> allowed = kitArenas.get(kit.id().toLowerCase(Locale.ROOT));
        return allowed == null || allowed.contains(arena.name());
    }

    /** Complete arenas the kit may be played on (free or not), sorted by id. */
    public List<Arena> availableFor(Kit kit) {
        List<Arena> list = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.isComplete() && isAllowed(kit, arena)) list.add(arena);
        }
        list.sort(Comparator.comparing(Arena::name));
        return list;
    }

    /** Every arena, complete or not, sorted by id. Used by the admin menu. */
    public List<Arena> sorted() {
        List<Arena> list = new ArrayList<>(arenas.values());
        list.sort(Comparator.comparing(Arena::name));
        return list;
    }

    public int allowedCount(String kitId) {
        Set<String> allowed = kitArenas.get(kitId.toLowerCase(Locale.ROOT));
        return allowed == null ? arenas.size() : allowed.size();
    }

    public enum ToggleResult { ENABLED, DISABLED, LAST_ARENA, UNKNOWN_ARENA }

    /**
     * Enables or disables one arena for a kit. The first toggle of an unrestricted kit starts from "all arenas".
     * The last enabled arena cannot be disabled (use {@link #clearRestriction} to allow everything instead).
     */
    public ToggleResult toggleKitArena(String kitId, String arenaName) {
        Arena arena = get(arenaName);
        if (arena == null) return ToggleResult.UNKNOWN_ARENA;
        String kit = kitId.toLowerCase(Locale.ROOT);
        Set<String> allowed = kitArenas.containsKey(kit) ? new LinkedHashSet<>(kitArenas.get(kit))
                : new LinkedHashSet<>(arenas.keySet());
        ToggleResult result;
        if (allowed.contains(arena.name())) {
            if (allowed.size() == 1) return ToggleResult.LAST_ARENA;
            allowed.remove(arena.name());
            result = ToggleResult.DISABLED;
        } else {
            allowed.add(arena.name());
            result = ToggleResult.ENABLED;
        }
        // enabling everything again is the same as having no restriction (arenas created later then count too)
        if (allowed.containsAll(arenas.keySet())) kitArenas.remove(kit);
        else kitArenas.put(kit, allowed);
        save();
        return result;
    }

    public void clearRestriction(String kitId) {
        if (kitArenas.remove(kitId.toLowerCase(Locale.ROOT)) != null) save();
    }

    /**
     * Reserves a free, complete arena the kit may be played on (state STARTING) and computes its boundary.
     * @param preferred id of the arena the players asked for, or {@code null} for a random one
     * @return the arena, or {@code null} when none (or not the preferred one) is available
     */
    public @Nullable Arena acquireFree(double boundaryPadding, Kit kit, @Nullable String preferred) {
        List<Arena> free = new ArrayList<>();
        for (Arena arena : arenas.values()) {
            if (arena.state() != ArenaState.WAITING || !arena.isComplete() || !isAllowed(kit, arena)) continue;
            if (preferred != null && !arena.name().equalsIgnoreCase(preferred)) continue;
            free.add(arena);
        }
        if (free.isEmpty()) return null;
        Arena chosen = free.get(ThreadLocalRandom.current().nextInt(free.size()));
        chosen.activate(boundaryPadding);
        chosen.setState(ArenaState.STARTING);
        return chosen;
    }

    public boolean hasActive() {
        for (Arena arena : arenas.values()) {
            if (arena.state() != ArenaState.WAITING) return true;
        }
        return false;
    }

    /** Finds the in-use arena whose boundary contains the location. */
    public @Nullable Arena findActiveAt(Location location) {
        if (location.getWorld() == null) return null;
        String world = location.getWorld().getName();
        for (Arena arena : arenas.values()) {
            if (arena.state() == ArenaState.WAITING) continue;
            BoundingBox bounds = arena.activeBounds();
            if (bounds == null || !world.equals(arena.worldName())) continue;
            if (bounds.contains(location.getX(), location.getY(), location.getZ())) return arena;
        }
        return null;
    }
}
