package org.chiterok.grandDuels.gui;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.utils.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * One configurable menu item (see menu/*.yml).
 * <pre>
 * slot: 11            # number (11), range ("8-12") or several numbers ("0, 1, 2, 5, 6, 7"; ranges may be mixed in: "0-2, 5")
 *                      # also accepted: slot: [0, 1, "2-8"] and the same under the key "slots"
 * material: CLOCK
 * amount: 1
 * name: "&amp;fText {placeholder}"
 * lore: ["&amp;7line"]  # list, or a single string
 * enchanted: false    # enchanted-item glint
 * action: SEND        # menu specific action id
 * condition: flag     # only shown when the menu sets this flag; "!flag" negates
 * </pre>
 */
public record MenuItem(String id, List<Integer> slots, Material material, int amount, @Nullable String name,
                       List<String> lore, boolean enchanted, @Nullable String action, @Nullable String condition) {

    public static MenuItem parse(String id, ConfigurationSection s, int size, Logger log) {
        String rawMaterial = s.getString("material", "STONE");
        Material material = Material.matchMaterial(rawMaterial);
        if (material == null || material.isAir() || !material.isItem()) {
            log.warning("Menu item '" + id + "': unknown material '" + rawMaterial + "', using BARRIER");
            material = Material.BARRIER;
        }
        List<Object> rawSlots = new ArrayList<>();
        if (s.contains("slot")) rawSlots.add(s.get("slot"));    // 5 | "8-12" | "0, 1, 2, 5" | [0, "2-4"]
        if (s.contains("slots")) rawSlots.add(s.get("slots"));

        String action = s.getString("action");
        String condition = s.getString("condition");
        return new MenuItem(id, parseSlots(rawSlots, size), material, Math.max(1, Math.min(64, s.getInt("amount", 1))),
                s.getString("name"), loreOf(s, "lore"), s.getBoolean("enchanted", false),
                action == null || action.isBlank() ? null : action.trim(),
                condition == null || condition.isBlank() ? null : condition.trim());
    }

    /** Reads a StringList that may also be written as a single string (an empty string means no lore). */
    public static List<String> loreOf(ConfigurationSection s, String key) {
        if (s.isString(key)) {
            String single = s.getString(key, "");
            return single.isEmpty() ? List.of() : List.of(single);
        }
        return List.copyOf(s.getStringList(key));
    }

    /**
     * Accepts a number, a "from-to" range, a comma separated list of those ("0, 1, 2, 5-7") or a (nested) collection
     * of any of them; silently drops slots outside the inventory and duplicates.
     */
    public static List<Integer> parseSlots(@Nullable Object raw, int size) {
        List<Integer> slots = new ArrayList<>();
        collect(raw, size, slots);
        return slots;
    }

    private static void collect(@Nullable Object value, int size, List<Integer> slots) {
        if (value == null) return;
        if (value instanceof Number number) {
            add(slots, number.intValue(), size);
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object element : collection) collect(element, size, slots);
            return;
        }
        for (String token : String.valueOf(value).split(",")) {
            String text = token.trim();
            if (text.isEmpty()) continue;
            int dash = text.indexOf('-', 1);
            try {
                if (dash > 0) {
                    int from = Integer.parseInt(text.substring(0, dash).trim());
                    int to = Integer.parseInt(text.substring(dash + 1).trim());
                    for (int i = Math.min(from, to); i <= Math.max(from, to); i++) add(slots, i, size);
                } else {
                    add(slots, Integer.parseInt(text), size);
                }
            } catch (NumberFormatException ignored) {
                // invalid entries are skipped
            }
        }
    }

    private static void add(List<Integer> slots, int slot, int size) {
        if (slot >= 0 && slot < size && !slots.contains(slot)) slots.add(slot);
    }

    public boolean visible(Set<String> flags) {
        if (condition == null) return true;
        return condition.startsWith("!") ? !flags.contains(condition.substring(1)) : flags.contains(condition);
    }

    public ItemStack build(Map<String, String> placeholders, Player viewer) {
        return decorate(new ItemStack(material, amount), name, lore, enchanted, placeholders, viewer);
    }

    /**
     * Applies name/lore/enchanted glint to {@code item}. A {@code null} name or empty lore leaves that part untouched.
     * PlaceholderAPI placeholders in name and lore are resolved for {@code viewer}.
     */
    public static ItemStack decorate(ItemStack item, @Nullable String name, List<String> lore, boolean enchanted,
                                     Map<String, String> placeholders, Player viewer) {
        item.editMeta(meta -> {
            if (name != null) meta.displayName(ColorUtil.colorizeItem(name, placeholders, viewer));
            if (!lore.isEmpty()) meta.lore(ColorUtil.colorizeItem(lore, placeholders, viewer));
            meta.addItemFlags(ItemFlag.values());
            if (enchanted) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }
}
