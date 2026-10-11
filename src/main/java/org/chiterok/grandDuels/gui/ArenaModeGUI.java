package org.chiterok.grandDuels.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.match.ArenaModeManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@code /arena} menu (menu/player/arena-mode.yml): choosing a standard kit enters the arena with it. */
public final class ArenaModeGUI extends GuiHolder {

    private final List<Kit> kits;
    private final Map<Integer, Kit> kitBySlot = new HashMap<>();
    private Map<Integer, String> actions = Map.of();
    private int page;
    private int pageCount = 1;

    public ArenaModeGUI(GrandDuels plugin, Player viewer) {
        super(plugin, viewer);
        this.kits = new ArrayList<>(plugin.kits().all()); // standard kits only
    }

    @Override
    public void open() {
        MenuDefinition def = plugin.menus().get(MenuManager.ARENA_MODE);
        List<Integer> slots = def.slots("kit-slots");
        if (slots.isEmpty()) {
            for (int i = 0; i < Math.max(1, def.size() - 9); i++) slots.add(i);
        }
        int perPage = slots.size();
        pageCount = Math.max(1, (int) Math.ceil(kits.size() / (double) perPage));
        page = Math.max(0, Math.min(page, pageCount - 1));

        Set<String> flags = new HashSet<>();
        if (page > 0) flags.add("has-previous");
        if (page < pageCount - 1) flags.add("has-next");
        if (kits.isEmpty()) flags.add("no-kits");
        Map<String, String> ph = Messages.ph("page", page + 1, "pages", pageCount,
                "in_arena", plugin.arenaMode().count());

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
        List<String> loreTemplate = template == null ? List.of("{kit_description}") : MenuItem.loreOf(template, "lore");
        boolean enchanted = template != null && template.getBoolean("enchanted", false);

        List<String> lore = new ArrayList<>();
        for (String line : loreTemplate) {
            if (line.trim().equals("{kit_description}")) lore.addAll(kit.description());
            else lore.add(line);
        }
        Map<String, String> local = new HashMap<>(ph);
        local.put("kit_name", kit.displayName());
        local.put("kit", kit.displayName());

        ItemStack icon = kit.icon().clone();
        icon.setAmount(1);
        icon.editMeta(meta -> meta.lore(null));
        return MenuItem.decorate(icon, name, lore, enchanted, local);
    }

    @Override
    public void onClick(int slot, ClickType click) {
        Kit kit = kitBySlot.get(slot);
        if (kit != null) {
            viewer.closeInventory();
            ArenaModeManager.JoinResult result = plugin.arenaMode().join(viewer, kit);
            switch (result) {
                case JOINED -> plugin.messages().send(viewer, "arenamode.joined", "kit", kit.displayName());
                case DISABLED -> plugin.messages().send(viewer, "arenamode.disabled");
                case NOT_CONFIGURED, WORLD_UNAVAILABLE -> plugin.messages().send(viewer, "arenamode.not-configured");
                case BUSY -> plugin.messages().send(viewer, "arenamode.busy");
                case NOT_STANDARD_KIT -> plugin.messages().send(viewer, "queue.standard-only");
                case FAILED -> plugin.messages().send(viewer, "duels.start-failed");
            }
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
