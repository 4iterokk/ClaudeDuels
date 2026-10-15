package org.chiterok.grandDuels.arena;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A group of interchangeable arenas ("sub-arenas"). Players pick a group; the plugin plays the duel on whichever
 * sub-arena of it is free. The name, icon and enabled flag live on the group, positions on the sub-arenas.
 */
public final class ArenaGroup {

    private final String id;
    private final Map<String, Arena> arenas = new ConcurrentHashMap<>();
    private volatile String displayName;
    private volatile Material displayMaterial;
    private volatile boolean enabled = true;

    public ArenaGroup(String id) {
        this.id = id.toLowerCase(Locale.ROOT);
    }

    public String id() {
        return id;
    }

    /** Custom name shown to players (scoreboard, messages, menus); the id when none is set. May contain color codes. */
    public String displayName() {
        String custom = displayName;
        return custom == null || custom.isBlank() ? id : custom;
    }

    public boolean hasCustomName() {
        String custom = displayName;
        return custom != null && !custom.isBlank();
    }

    public void setDisplayName(@Nullable String displayName) {
        this.displayName = displayName == null || displayName.isBlank() ? null : displayName;
    }

    /** Item shown for this group in the arena selector, or {@code null} for the menu's default icons. */
    public @Nullable Material displayMaterial() {
        return displayMaterial;
    }

    public void setDisplayMaterial(@Nullable Material material) {
        this.displayMaterial = material;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public @Nullable Arena arena(String name) {
        return arenas.get(name.toLowerCase(Locale.ROOT));
    }

    /** Adds a sub-arena. @return the new arena, or {@code null} if the name is taken. */
    public @Nullable Arena addArena(String name) {
        Arena arena = new Arena(this, name.toLowerCase(Locale.ROOT));
        return arenas.putIfAbsent(arena.name(), arena) == null ? arena : null;
    }

    public @Nullable Arena removeArena(String name) {
        return arenas.remove(name.toLowerCase(Locale.ROOT));
    }

    /** Sub-arenas sorted by name. */
    public List<Arena> arenas() {
        List<Arena> list = new ArrayList<>(arenas.values());
        list.sort(Comparator.comparing(Arena::name));
        return list;
    }

    public List<String> arenaNames() {
        return arenas().stream().map(Arena::name).toList();
    }

    public int size() {
        return arenas.size();
    }

    /** Sub-arenas that can host a duel at all: enabled and fully set up. */
    public List<Arena> usableArenas() {
        List<Arena> list = new ArrayList<>();
        for (Arena arena : arenas()) {
            if (arena.enabled() && arena.isComplete()) list.add(arena);
        }
        return list;
    }

    /** The group is switched on and has at least one usable sub-arena (busy or not). */
    public boolean isPlayable() {
        return enabled && !usableArenas().isEmpty();
    }

    /** Usable sub-arenas that are idle right now. */
    public List<Arena> freeArenas() {
        if (!enabled) return List.of();
        List<Arena> list = new ArrayList<>();
        for (Arena arena : usableArenas()) {
            if (arena.state() == ArenaState.WAITING) list.add(arena);
        }
        return list;
    }

    /** True when no sub-arena is idle (all busy, disabled or incomplete). */
    public boolean isBusy() {
        return freeArenas().isEmpty();
    }

    public boolean isInUse() {
        for (Arena arena : arenas.values()) {
            if (arena.state() != ArenaState.WAITING) return true;
        }
        return false;
    }
}
