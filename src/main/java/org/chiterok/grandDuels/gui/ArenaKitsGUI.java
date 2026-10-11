package org.chiterok.grandDuels.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.kit.Kit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** ADMIN menu (menu/admin/arena-kits.yml): lists the standard kits; clicking one opens {@link KitArenasGUI}. */
public final class ArenaKitsGUI extends GuiHolder {

    private static final String PERMISSION = "grandduels.admin";

    private final List<Kit> kits;
    private final Map<Integer, Kit> kitBySlot = new HashMap<>();
    private Map<Integer, String> actions = Map.of();
    private int page;
    private int pageCount = 1;

    public ArenaKitsGUI(GrandDuels plugin, Player viewer, int page) {
        super(plugin, viewer);
        this.kits = new ArrayList<>(plugin.kits().all());
        this.page = Math.max(0, page);
    }

    @Override
    public void open() {
        MenuDefinition def = plugin.menus().get(MenuManager.ADMIN_ARENA_KITS);
        List<Integer> slots = def.slots("kit-slots");
        if (slots.isEmpty()) {
            for (int i = 0; i < Math.max(1, def.size() - 9); i++) slots.add(i);
        }
        int perPage = slots.size();
        pageCount = Math.max(1, (int) Math.ceil(kits.size() / (double) perPage));
        page = Math.min(page, pageCount - 1);

        Set<String> flags = new HashSet<>();
        if (page > 0) flags.add("has-previous");
        if (page < pageCount - 1) flags.add("has-next");
        if (kits.isEmpty()) flags.add("no-kits");
        Map<String, String> ph = Messages.ph("page", page + 1, "pages", pageCount);

        create(def, ph);
        Rendered rendered = render(def, flags, ph);
        this.actions = rendered.actions();

        kitBySlot.clear();
        for (int i = 0; i < perPage; i++) {
            int slot = slots.get(i);
            int index = page * perPage + i;
            if (index < kits.size()) {
                Kit kit = kits.get(index);
                getInventory().setItem(slot, kitItem(def, kit, ph));
                kitBySlot.put(slot, kit);
                actions.remove(slot);
            } else if (!rendered.occupied().contains(slot)) {
                getInventory().setItem(slot, null);
            }
        }
        viewer.openInventory(getInventory());
    }

    private ItemStack kitItem(MenuDefinition def, Kit kit, Map<String, String> ph) {
        ConfigurationSection template = def.root().getConfigurationSection("kit-item");
        String name = template == null ? "{kit_name}" : template.getString("name", "{kit_name}");
        List<String> lore = template == null ? List.of("&7{allowed}/{total}") : MenuItem.loreOf(template, "lore");
        boolean restricted = plugin.arenas().isRestricted(kit.id());
        boolean glint = restricted && (template == null || template.getBoolean("enchanted-when-restricted", true));

        Map<String, String> local = new HashMap<>(ph);
        local.put("kit_name", kit.displayName());
        local.put("kit_id", kit.id());
        local.put("allowed", String.valueOf(plugin.arenas().allowedCount(kit.id())));
        local.put("total", String.valueOf(plugin.arenas().all().size()));
        local.put("restriction", def.root().getString("restriction." + (restricted ? "limited" : "none"), ""));

        ItemStack icon = kit.icon().clone();
        icon.setAmount(1);
        icon.editMeta(meta -> meta.lore(null));
        return MenuItem.decorate(icon, name, lore, glint, local);
    }

    @Override
    public void onClick(int slot, ClickType click) {
        if (!viewer.hasPermission(PERMISSION)) {
            viewer.closeInventory();
            plugin.messages().send(viewer, "general.no-permission");
            return;
        }
        Kit kit = kitBySlot.get(slot);
        if (kit != null) {
            int returnPage = page;
            new KitArenasGUI(plugin, viewer, kit, 0, () -> new ArenaKitsGUI(plugin, viewer, returnPage).open()).open();
            return;
        }
        String action = actions.get(slot);
        if (action == null) return;
        switch (action.toUpperCase(Locale.ROOT)) {
            case "PREVIOUS_PAGE" -> {
                if (page > 0) {
                    page--;
                    open();
                }
            }
            case "NEXT_PAGE" -> {
                if (page < pageCount - 1) {
                    page++;
                    open();
                }
            }
            case "CLOSE" -> viewer.closeInventory();
            default -> { /* unknown action ids are ignored */ }
        }
    }
}
