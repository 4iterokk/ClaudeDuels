package org.chiterok.grandDuels.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Parsed menu file: title, size, filler and static items. The raw section stays available for menu specific keys. */
public final class MenuDefinition {

    private final String id;
    private final String title;
    private final int size;
    private final @Nullable MenuItem filler;
    private final List<MenuItem> items;
    private final ConfigurationSection root;

    private MenuDefinition(String id, String title, int size, @Nullable MenuItem filler, List<MenuItem> items,
                           ConfigurationSection root) {
        this.id = id;
        this.title = title;
        this.size = size;
        this.filler = filler;
        this.items = items;
        this.root = root;
    }

    public static MenuDefinition parse(String id, ConfigurationSection root, Logger log) {
        int size = root.getInt("size", 27);
        if (size < 9 || size > 54 || size % 9 != 0) {
            int fixed = Math.max(9, Math.min(54, (size + 8) / 9 * 9));
            log.warning("Menu '" + id + "': size must be a multiple of 9 between 9 and 54 (got " + size + "), using " + fixed);
            size = fixed;
        }

        MenuItem filler = null;
        ConfigurationSection fillerSection = root.getConfigurationSection("filler");
        if (fillerSection != null) filler = MenuItem.parse("filler", fillerSection, size, log);

        List<MenuItem> items = new ArrayList<>();
        ConfigurationSection itemSection = root.getConfigurationSection("items");
        if (itemSection != null) {
            for (String key : itemSection.getKeys(false)) {
                ConfigurationSection section = itemSection.getConfigurationSection(key);
                if (section != null) items.add(MenuItem.parse(key, section, size, log));
            }
        }
        String title = root.getString("menu_title", root.getString("title", "&8" + id));
        return new MenuDefinition(id, title, size, filler, List.copyOf(items), root);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public int size() {
        return size;
    }

    public @Nullable MenuItem filler() {
        return filler;
    }

    public List<MenuItem> items() {
        return items;
    }

    public ConfigurationSection root() {
        return root;
    }

    /** Reads a slot spec such as {@code kit-slots: ["0-44"]}, {@code kit-slots: 0-44} or {@code kit-slots: 0, 1, 2}. */
    public List<Integer> slots(String key) {
        return MenuItem.parseSlots(root.get(key), size());
    }
}
