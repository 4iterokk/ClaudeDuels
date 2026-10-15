package org.chiterok.grandDuels.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.utils.ColorUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Base class of all plugin GUIs. {@link org.chiterok.grandDuels.runtime.GuiListener} routes events here. */
public abstract class GuiHolder implements InventoryHolder {

    /** Result of {@link #render}: which slot triggers which action, and which slots hold configured items. */
    protected record Rendered(Map<Integer, String> actions, Set<Integer> occupied) {}

    protected final ClaudeDuels plugin;
    protected final Player viewer;
    private Inventory inventory;

    protected GuiHolder(ClaudeDuels plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    protected final Inventory create(MenuDefinition definition, Map<String, String> placeholders) {
        this.inventory = Bukkit.createInventory(this, definition.size(), ColorUtil.colorize(definition.title(), placeholders, viewer));
        return inventory;
    }

    /** For GUIs that build their inventory themselves instead of from a menu definition. */
    protected final void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    /** Fills the filler, then every visible static item of the definition (later items win on shared slots). */
    protected final Rendered render(MenuDefinition definition, Set<String> flags, Map<String, String> placeholders) {
        inventory.clear();
        MenuItem filler = definition.filler();
        if (filler != null) {
            ItemStack fill = filler.build(placeholders, viewer);
            for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, fill.clone());
        }
        Map<Integer, String> actions = new HashMap<>();
        Set<Integer> occupied = new HashSet<>();
        for (MenuItem item : definition.items()) {
            if (!item.visible(flags)) continue;
            ItemStack built = item.build(placeholders, viewer);
            for (int slot : item.slots()) {
                inventory.setItem(slot, built.clone());
                occupied.add(slot);
                if (item.action() != null) actions.put(slot, item.action());
                else actions.remove(slot);
            }
        }
        return new Rendered(actions, occupied);
    }

    /** Builds the inventory and opens it for the viewer. */
    public abstract void open();

    /** @param slot raw slot inside the top inventory */
    public abstract void onClick(int slot, ClickType click);

    /** Called when the viewer closes this inventory. */
    public void onClose() {
        // optional
    }
}
