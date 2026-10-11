package org.chiterok.grandDuels.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.cooldown.RuleDefaults;
import org.chiterok.grandDuels.cooldown.RuleType;
import org.chiterok.grandDuels.data.PlayerPreferences;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Personal cooldowns and restrictions (menu/cooldowns.yml): item cooldowns, item bans, elytra and riptide.
 * Changes are stored in the player's preferences and applied automatically to every request the player sends.
 * The server defaults are defined in the same file ({@code default-cooldown}, {@code default-banned}).
 */
public final class CooldownMenuGUI extends GuiHolder {

    private final PlayerPreferences prefs;
    private final @Nullable Runnable back;
    private final Map<Integer, RuleType> typeBySlot = new HashMap<>();
    private Map<Integer, String> actions = Map.of();

    public CooldownMenuGUI(GrandDuels plugin, Player viewer, @Nullable Runnable back) {
        super(plugin, viewer);
        this.prefs = plugin.preferences().get(viewer.getUniqueId());
        this.back = back;
    }

    @Override
    public void open() {
        MenuDefinition def = plugin.menus().get(MenuManager.COOLDOWNS);
        create(def, Map.of());
        refresh(def);
        viewer.openInventory(getInventory());
    }

    private void refresh(MenuDefinition def) {
        Set<String> flags = new HashSet<>();
        if (prefs.customCooldowns()) flags.add("custom-cooldowns");
        if (back != null) flags.add("has-back");
        Rendered rendered = render(def, flags, Map.of());
        this.actions = rendered.actions();
        typeBySlot.clear();

        ConfigurationSection items = def.root().getConfigurationSection("cooldown-items");
        if (items == null) return;
        List<String> cooldownLore = MenuItem.loreOf(def.root(), "cooldown-lore");
        List<String> restrictionLore = MenuItem.loreOf(def.root(), "restriction-lore");
        for (RuleType type : RuleType.values()) {
            ConfigurationSection section = items.getConfigurationSection(type.configKey());
            if (section == null) continue;
            MenuItem item = MenuItem.parse(type.configKey(), section, def.size(), plugin.getLogger());
            List<String> lore = section.contains("lore") ? item.lore() : type.hasCooldown() ? cooldownLore : restrictionLore;
            ItemStack built = MenuItem.decorate(new ItemStack(item.material(), item.amount()), item.name(), lore,
                    item.enchanted(), placeholders(def, type));
            for (int slot : item.slots()) {
                getInventory().setItem(slot, built.clone());
                typeBySlot.put(slot, type);
                actions.remove(slot);
            }
        }
    }

    private Map<String, String> placeholders(MenuDefinition def, RuleType type) {
        RuleDefaults defaults = plugin.menus().defaults();
        boolean banned = prefs.isBanned(type, defaults.isBanned(type));
        double seconds = prefs.cooldownSeconds(type, defaults.seconds(type));
        return Messages.ph("state", stateText(def, type, banned, seconds),
                "seconds", format(seconds),
                "default", stateText(def, type, defaults.isBanned(type), defaults.seconds(type)),
                "step", format(step(def, "step", 1.0)), "shift_step", format(step(def, "shift-step", 10.0)));
    }

    private static String stateText(MenuDefinition def, RuleType type, boolean banned, double seconds) {
        String key = banned ? "banned" : !type.hasCooldown() ? "allowed" : seconds <= 0.0 ? "none" : "cooldown";
        return def.root().getString("states." + key, key).replace("{seconds}", format(seconds));
    }

    private static double step(MenuDefinition def, String key, double fallback) {
        return Math.max(0.1, def.root().getDouble("settings." + key, fallback));
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public void onClick(int slot, ClickType click) {
        MenuDefinition def = plugin.menus().get(MenuManager.COOLDOWNS);
        RuleType type = typeBySlot.get(slot);
        if (type != null) {
            adjust(def, type, click);
            refresh(def);
            return;
        }
        String action = actions.get(slot);
        if (action == null) return;
        switch (action.toUpperCase(Locale.ROOT)) {
            case "TOGGLE_COOLDOWNS" -> {
                prefs.setCustomCooldowns(!prefs.customCooldowns());
                refresh(def);
            }
            case "RESET" -> {
                prefs.reset();
                refresh(def);
            }
            case "BACK" -> {
                if (back != null) back.run();
                else viewer.closeInventory();
            }
            case "CLOSE" -> viewer.closeInventory();
            default -> { /* unknown action ids are ignored */ }
        }
    }

    private void adjust(MenuDefinition def, RuleType type, ClickType click) {
        RuleDefaults defaults = plugin.menus().defaults();
        boolean togglesBan = switch (click) {
            case MIDDLE, DROP, CONTROL_DROP, SWAP_OFFHAND -> true;
            case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT -> !type.hasCooldown(); // ban-only rules: any click toggles
            default -> false;
        };
        if (togglesBan) {
            prefs.toggleBanned(type, defaults.isBanned(type));
            return;
        }
        if (!type.hasCooldown()) return;

        double step = step(def, "step", 1.0);
        double shift = step(def, "shift-step", 10.0);
        double max = Math.max(0.0, def.root().getDouble("settings.max-seconds", 300.0));
        double current = prefs.cooldownSeconds(type, defaults.seconds(type));
        double next = switch (click) {
            case LEFT -> current + step;
            case RIGHT -> current - step;
            case SHIFT_LEFT -> current + shift;
            case SHIFT_RIGHT -> current - shift;
            default -> current;
        };
        double clamped = Math.max(0.0, Math.min(max, next));
        prefs.setCooldown(type, Math.round(clamped * 10.0) / 10.0);
    }

    @Override
    public void onClose() {
        plugin.preferences().save();
    }
}
