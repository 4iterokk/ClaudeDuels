package org.chiterok.grandDuels.gui;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.utils.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only preview of a kit's contents. The layout (slots of the hotbar, inventory, armor, offhand and effects),
 * the filler and all other items come from menu/player/kit-preview.yml.
 */
public final class KitPreviewGUI extends GuiHolder {

    private final Kit kit;
    private final Runnable back;
    private Map<Integer, String> actions = Map.of();

    /** @param back runs when the viewer clicks an item with action BACK (normally reopens the previous menu) */
    public KitPreviewGUI(GrandDuels plugin, Player viewer, Kit kit, Runnable back) {
        super(plugin, viewer);
        this.kit = kit;
        this.back = back;
    }

    @Override
    public void open() {
        MenuDefinition definition = plugin.menus().get(MenuManager.KIT_PREVIEW);
        Map<String, String> ph = Messages.ph("kit", kit.displayName());
        Inventory inventory = create(definition, ph);
        actions = render(definition, Set.of(), ph).actions();

        List<Integer> hotbar = definition.slots("hotbar-slots");
        List<Integer> main = definition.slots("inventory-slots");
        for (Map.Entry<Integer, ItemStack> entry : kit.items().entrySet()) {
            int kitSlot = entry.getKey();
            List<Integer> target = kitSlot < 9 ? hotbar : main;
            int index = kitSlot < 9 ? kitSlot : kitSlot - 9;
            if (index < target.size()) inventory.setItem(target.get(index), entry.getValue().clone());
        }
        place(inventory, definition, "helmet-slot", kit.helmet());
        place(inventory, definition, "chestplate-slot", kit.chestplate());
        place(inventory, definition, "leggings-slot", kit.leggings());
        place(inventory, definition, "boots-slot", kit.boots());
        place(inventory, definition, "offhand-slot", kit.offhand());
        if (!kit.effects().isEmpty()) place(inventory, definition, "effects-slot", effectsItem(definition));

        viewer.openInventory(inventory);
    }

    /** Puts {@code item} into the slot configured under {@code key} ({@code -1} or an invalid slot hides it). */
    private static void place(Inventory inventory, MenuDefinition definition, String key, ItemStack item) {
        if (item == null) return;
        int slot = definition.root().getInt(key, -1);
        if (slot >= 0 && slot < inventory.getSize()) inventory.setItem(slot, item.clone());
    }

    private ItemStack effectsItem(MenuDefinition definition) {
        ConfigurationSection section = definition.root().getConfigurationSection("effects-item");
        Material material = Material.POTION;
        String name = "&dEffects";
        String line = "&7- &f{effect} {level} &7({duration})";
        String infinite = "infinite";
        if (section != null) {
            Material configured = Material.matchMaterial(section.getString("material", "POTION"));
            if (configured != null && !configured.isAir() && configured.isItem()) material = configured;
            name = section.getString("name", name);
            line = section.getString("line", line);
            infinite = section.getString("infinite", infinite);
        }
        List<String> lore = new ArrayList<>();
        for (PotionEffect effect : kit.effects()) {
            String duration = effect.getDuration() < 0 ? infinite : TimeUtil.mmss(effect.getDuration() / 20L);
            lore.add(line.replace("{effect}", effect.getType().getKey().getKey().replace('_', ' ').toLowerCase(Locale.ROOT))
                    .replace("{level}", String.valueOf(effect.getAmplifier() + 1))
                    .replace("{duration}", duration));
        }
        return MenuItem.decorate(new ItemStack(material), name, lore, false, Map.of());
    }

    @Override
    public void onClick(int slot, ClickType click) {
        String action = actions.get(slot);
        if (action == null) return;
        switch (action.toUpperCase(Locale.ROOT)) {
            case "BACK" -> back.run();
            case "CLOSE" -> viewer.closeInventory();
            default -> { /* unknown action ids are ignored */ }
        }
    }
}
