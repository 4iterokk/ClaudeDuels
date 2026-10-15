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
 * Registry and persistence (arenas.yml) of arena groups and their sub-arenas, including which groups each standard
 * kit may be played on. A kit without an entry in {@code kit-arenas} can be played on every group.
 * Mutations happen on the main thread.
 */
public final class ArenaManager {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final ClaudeDuels plugin;
    private final Map<String, ArenaGroup> groups = new ConcurrentHashMap<>();
    /** Kit id -> ids of the groups the kit may use. Absent = unrestricted. */
    private final Map<String, Set<String>> kitArenas = new ConcurrentHashMap<>();

    public ArenaManager(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    public static boolean isValidName(String name) {
        return VALID_NAME.matcher(name).matches();
    }

    // ---------------------------------------------------------------- persistence

    public void load() {
        groups.clear();
        kitArenas.clear();
        YamlConfiguration yaml = plugin.configs().arenasFile().get();
        ConfigurationSection root = yaml.getConfigurationSection("groups");
        int subCount = 0;
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) continue;
                ArenaGroup group = new ArenaGroup(key);
                group.setDisplayName(section.getString("display-name"));
                group.setDisplayMaterial(parseIcon(section.getString("icon")));
                group.setEnabled(section.getBoolean("enabled", true));
                ConfigurationSection subs = section.getConfigurationSection("arenas");
                if (subs != null) {
                    for (String subKey : subs.getKeys(false)) {
                        ConfigurationSection subSection = subs.getConfigurationSection(subKey);
                        if (subSection == null) continue;
                        Arena arena = group.addArena(subKey);
                        if (arena == null) continue;
                        arena.setPos1(StoredLocation.read(subSection.getConfigurationSection("pos1")));
                        arena.setPos2(StoredLocation.read(subSection.getConfigurationSection("pos2")));
                        arena.setEnabled(subSection.getBoolean("enabled", true));
                        subCount++;
                    }
                }
                groups.put(group.id(), group);
            }
        }
        ConfigurationSection restrictions = yaml.getConfigurationSection("kit-arenas");
        if (restrictions != null) {
            for (String kitId : restrictions.getKeys(false)) {
                Set<String> allowed = new LinkedHashSet<>();
                for (String name : restrictions.getStringList(kitId)) allowed.add(name.toLowerCase(Locale.ROOT));
                allowed.retainAll(groups.keySet());
                if (!allowed.isEmpty()) kitArenas.put(kitId.toLowerCase(Locale.ROOT), allowed);
            }
        }
        plugin.getLogger().info("Loaded " + groups.size() + " arena group(s) with " + subCount + " arena(s).");
    }

    public void save() {
        YamlConfiguration yaml = plugin.configs().arenasFile().get();
        yaml.set("arenas", null); // pre-group format, no longer used
        yaml.set("groups", null);
        for (ArenaGroup group : groups.values()) {
            ConfigurationSection section = yaml.createSection("groups." + group.id());
            if (group.hasCustomName()) section.set("display-name", group.displayName());
            if (group.displayMaterial() != null) section.set("icon", group.displayMaterial().name());
            section.set("enabled", group.enabled());
            for (Arena arena : group.arenas()) {
                ConfigurationSection sub = section.createSection("arenas." + arena.name());
                StoredLocation p1 = arena.pos1();
                StoredLocation p2 = arena.pos2();
                if (p1 != null) p1.write(sub.createSection("pos1"));
                if (p2 != null) p2.write(sub.createSection("pos2"));
                sub.set("enabled", arena.enabled());
            }
        }
        yaml.set("kit-arenas", null);
        for (Map.Entry<String, Set<String>> entry : kitArenas.entrySet()) {
            yaml.set("kit-arenas." + entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        plugin.configs().arenasFile().save();
    }

    // ---------------------------------------------------------------- groups

    public @Nullable ArenaGroup group(String id) {
        return groups.get(id.toLowerCase(Locale.ROOT));
    }

    /** Every group sorted by id (complete or not, enabled or not). */
    public List<ArenaGroup> groups() {
        List<ArenaGroup> list = new ArrayList<>(groups.values());
        list.sort(Comparator.comparing(ArenaGroup::id));
        return list;
    }

    public List<String> groupNames() {
        return groups().stream().map(ArenaGroup::id).toList();
    }

    public int groupCount() {
        return groups.size();
    }

    /** @return the new group, or {@code null} if one with that id exists. */
    public @Nullable ArenaGroup createGroup(String id) {
        ArenaGroup group = new ArenaGroup(id);
        if (groups.putIfAbsent(group.id(), group) != null) return null;
        save();
        return group;
    }

    /** @return false if the group does not exist or one of its arenas is in use. */
    public boolean deleteGroup(String id) {
        ArenaGroup group = group(id);
        if (group == null || group.isInUse()) return false;
        groups.remove(group.id());
        for (Map.Entry<String, Set<String>> entry : List.copyOf(kitArenas.entrySet())) {
            Set<String> remaining = new LinkedHashSet<>(entry.getValue());
            remaining.remove(group.id());
            // a kit whose only group was deleted becomes unrestricted again instead of unplayable
            if (remaining.isEmpty()) kitArenas.remove(entry.getKey());
            else kitArenas.put(entry.getKey(), remaining);
        }
        save();
        return true;
    }

    /** Sets (or, with null/blank, clears) the custom name of a group. @return false if it does not exist. */
    public boolean setDisplayName(String id, @Nullable String displayName) {
        ArenaGroup group = group(id);
        if (group == null) return false;
        group.setDisplayName(displayName);
        save();
        return true;
    }

    /** Sets (or, with null, clears) the selector icon of a group. @return false if it does not exist. */
    public boolean setDisplayMaterial(String id, @Nullable Material material) {
        ArenaGroup group = group(id);
        if (group == null) return false;
        group.setDisplayMaterial(material);
        save();
        return true;
    }

    /** Switches a group on/off (matches already running there finish normally). @return the new state, or null. */
    public @Nullable Boolean toggleGroup(String id) {
        ArenaGroup group = group(id);
        if (group == null) return null;
        group.setEnabled(!group.enabled());
        save();
        return group.enabled();
    }

    /** A usable menu icon: an existing, non-air item material; {@code null} for anything else. */
    public static @Nullable Material parseIcon(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        Material material = Material.matchMaterial(raw.trim());
        return material == null || material.isAir() || !material.isItem() ? null : material;
    }

    // ---------------------------------------------------------------- sub-arenas

    public @Nullable Arena arena(String groupId, String name) {
        ArenaGroup group = group(groupId);
        return group == null ? null : group.arena(name);
    }

    /** @return the new sub-arena, or {@code null} if the group is unknown or the name is taken. */
    public @Nullable Arena addArena(String groupId, String name) {
        ArenaGroup group = group(groupId);
        if (group == null) return null;
        Arena arena = group.addArena(name);
        if (arena != null) save();
        return arena;
    }

    /** @return false if the group or arena does not exist, or the arena is in use. */
    public boolean removeArena(String groupId, String name) {
        Arena arena = arena(groupId, name);
        if (arena == null || arena.state() != ArenaState.WAITING) return false;
        arena.group().removeArena(name);
        save();
        return true;
    }

    /** Switches one sub-arena on/off (maintenance). @return the new state, or {@code null} if unknown. */
    public @Nullable Boolean toggleArena(String groupId, String name) {
        Arena arena = arena(groupId, name);
        if (arena == null) return null;
        arena.setEnabled(!arena.enabled());
        save();
        return arena.enabled();
    }

    public int arenaCount() {
        int total = 0;
        for (ArenaGroup group : groups.values()) total += group.size();
        return total;
    }

    // ---------------------------------------------------------------- groups per kit

    /** True when an admin picked a list of groups for the standard kit. */
    public boolean isRestricted(String kitId) {
        return kitArenas.containsKey(kitId.toLowerCase(Locale.ROOT));
    }

    /** Player kits may use every group; standard kits follow the admin's list (unrestricted when none). */
    public boolean isAllowed(Kit kit, ArenaGroup group) {
        if (kit.owner() != null) return true;
        Set<String> allowed = kitArenas.get(kit.id().toLowerCase(Locale.ROOT));
        return allowed == null || allowed.contains(group.id());
    }

    /** Playable groups (enabled, with a usable sub-arena) the kit may be played on, busy or not, sorted by id. */
    public List<ArenaGroup> availableFor(Kit kit) {
        List<ArenaGroup> list = new ArrayList<>();
        for (ArenaGroup group : groups.values()) {
            if (group.isPlayable() && isAllowed(kit, group)) list.add(group);
        }
        list.sort(Comparator.comparing(ArenaGroup::id));
        return list;
    }

    public int allowedCount(String kitId) {
        Set<String> allowed = kitArenas.get(kitId.toLowerCase(Locale.ROOT));
        return allowed == null ? groups.size() : allowed.size();
    }

    public enum ToggleResult { ENABLED, DISABLED, LAST_ARENA, UNKNOWN_ARENA }

    /**
     * Enables or disables one group for a kit. The first toggle of an unrestricted kit starts from "all groups".
     * The last enabled group cannot be disabled (use {@link #clearRestriction} to allow everything instead).
     */
    public ToggleResult toggleKitArena(String kitId, String groupId) {
        ArenaGroup group = group(groupId);
        if (group == null) return ToggleResult.UNKNOWN_ARENA;
        String kit = kitId.toLowerCase(Locale.ROOT);
        Set<String> allowed = kitArenas.containsKey(kit) ? new LinkedHashSet<>(kitArenas.get(kit))
                : new LinkedHashSet<>(groups.keySet());
        ToggleResult result;
        if (allowed.contains(group.id())) {
            if (allowed.size() == 1) return ToggleResult.LAST_ARENA;
            allowed.remove(group.id());
            result = ToggleResult.DISABLED;
        } else {
            allowed.add(group.id());
            result = ToggleResult.ENABLED;
        }
        // enabling everything again is the same as having no restriction (groups created later then count too)
        if (allowed.containsAll(groups.keySet())) kitArenas.remove(kit);
        else kitArenas.put(kit, allowed);
        save();
        return result;
    }

    public void clearRestriction(String kitId) {
        if (kitArenas.remove(kitId.toLowerCase(Locale.ROOT)) != null) save();
    }

    // ---------------------------------------------------------------- matchmaking

    /**
     * Reserves a free sub-arena (state STARTING) of a group the kit may be played on and computes its boundary.
     * @param preferred id of the group the players asked for, or {@code null} for a random one
     * @return the sub-arena, or {@code null} when none (or none in the preferred group) is free
     */
    public @Nullable Arena acquireFree(double boundaryPadding, Kit kit, @Nullable String preferred) {
        List<Arena> free = new ArrayList<>();
        for (ArenaGroup group : groups.values()) {
            if (!isAllowed(kit, group)) continue;
            if (preferred != null && !group.id().equalsIgnoreCase(preferred)) continue;
            free.addAll(group.freeArenas());
        }
        if (free.isEmpty()) return null;
        Arena chosen = free.get(ThreadLocalRandom.current().nextInt(free.size()));
        chosen.activate(boundaryPadding);
        chosen.setState(ArenaState.STARTING);
        return chosen;
    }

    public boolean hasActive() {
        for (ArenaGroup group : groups.values()) {
            if (group.isInUse()) return true;
        }
        return false;
    }

    /** Finds the in-use sub-arena whose boundary contains the location. */
    public @Nullable Arena findActiveAt(Location location) {
        if (location.getWorld() == null) return null;
        String world = location.getWorld().getName();
        for (ArenaGroup group : groups.values()) {
            for (Arena arena : group.arenas()) {
                if (arena.state() == ArenaState.WAITING) continue;
                BoundingBox bounds = arena.activeBounds();
                if (bounds == null || !world.equals(arena.worldName())) continue;
                if (bounds.contains(location.getX(), location.getY(), location.getZ())) return arena;
            }
        }
        return null;
    }
}
