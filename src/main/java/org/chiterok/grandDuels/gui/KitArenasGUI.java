package org.chiterok.grandDuels.gui;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.Arena;
import org.chiterok.grandDuels.arena.ArenaManager;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.kit.Kit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ADMIN menu (menu/admin/kit-arenas.yml): toggles the arenas one kit can be played on. Changes are saved to arenas.yml
 * immediately and apply to duels started afterwards.
 */
public final class KitArenasGUI extends GuiHolder {

    private static final String PERMISSION = "grandduels.admin";

    private final Kit kit;
    private final Runnable onBack;
    private final Map<Integer, Arena> arenaBySlot = new HashMap<>();
    private Map<Integer, String> actions = Map.of();
    private int page;
    private int pageCount = 1;

    public KitArenasGUI(ClaudeDuels plugin, Player viewer, Kit kit, int page, Runnable onBack) {
        super(plugin, viewer);
        this.kit = kit;
        this.page = Math.max(0, page);
        this.onBack = onBack;
    }

    @Override
    public void open() {
        MenuDefinition def = plugin.menus().get(MenuManager.ADMIN_KIT_ARENAS);
        create(def, placeholders());
        populate(def);
        viewer.openInventory(getInventory());
    }

    private Map<String, String> placeholders() {
        return Messages.ph("page", page + 1, "pages", pageCount, "kit", kit.displayName(), "kit_id", kit.id(),
                "allowed", plugin.arenas().allowedCount(kit.id()), "total", plugin.arenas().all().size());
    }

    /** Re-renders the open inventory in place (no reopening, so the cursor position is kept). */
    private void populate(MenuDefinition def) {
        List<Arena> arenas = plugin.arenas().sorted();
        List<Integer> slots = def.slots("arena-slots");
        if (slots.isEmpty()) {
            for (int i = 0; i < Math.max(1, def.size() - 9); i++) slots.add(i);
        }
        int perPage = slots.size();
        pageCount = Math.max(1, (int) Math.ceil(arenas.size() / (double) perPage));
        page = Math.max(0, Math.min(page, pageCount - 1));

        Set<String> flags = new HashSet<>();
        if (page > 0) flags.add("has-previous");
        if (page < pageCount - 1) flags.add("has-next");
        if (arenas.isEmpty()) flags.add("no-arenas");
        if (plugin.arenas().isRestricted(kit.id())) flags.add("restricted");
        Map<String, String> ph = placeholders();

        Rendered rendered = render(def, flags, ph);
        this.actions = rendered.actions();

        arenaBySlot.clear();
        for (int i = 0; i < perPage; i++) {
            int slot = slots.get(i);
            int index = page * perPage + i;
            if (index < arenas.size()) {
                Arena arena = arenas.get(index);
                getInventory().setItem(slot, arenaItem(def, arena, ph));
                arenaBySlot.put(slot, arena);
                actions.remove(slot);
            } else if (!rendered.occupied().contains(slot)) {
                getInventory().setItem(slot, null);
            }
        }
    }

    private ItemStack arenaItem(MenuDefinition def, Arena arena, Map<String, String> ph) {
        ConfigurationSection template = def.root().getConfigurationSection("arena-item");
        boolean allowed = plugin.arenas().isAllowed(kit, arena);
        String name = template == null ? "{arena_name}" : template.getString("name", "{arena_name}");
        List<String> lore = template == null ? List.of() : MenuItem.loreOf(template, allowed ? "lore-allowed" : "lore-denied");
        Material material = allowed ? Material.LIME_STAINED_GLASS : Material.RED_STAINED_GLASS;
        if (template != null) {
            Material parsed = Material.matchMaterial(template.getString(allowed ? "material-allowed" : "material-denied", ""));
            if (parsed != null && parsed.isItem() && !parsed.isAir()) material = parsed;
        }
        boolean glint = allowed && (template == null || template.getBoolean("enchanted-when-allowed", true));

        Map<String, String> local = new HashMap<>(ph);
        local.put("arena_name", arena.displayName());
        local.put("arena_id", arena.name());
        return MenuItem.decorate(new ItemStack(material), name, lore, glint, local, viewer);
    }

    @Override
    public void onClick(int slot, ClickType click) {
        if (!viewer.hasPermission(PERMISSION)) {
            viewer.closeInventory();
            plugin.messages().send(viewer, "general.no-permission");
            return;
        }
        MenuDefinition def = plugin.menus().get(MenuManager.ADMIN_KIT_ARENAS);
        Arena arena = arenaBySlot.get(slot);
        if (arena != null) {
            ArenaManager.ToggleResult result = plugin.arenas().toggleKitArena(kit.id(), arena.name());
            switch (result) {
                case ENABLED -> plugin.messages().actionBar(viewer, "arena.kit-enabled", "arena", arena.displayName(),
                        "kit", kit.displayName());
                case DISABLED -> plugin.messages().actionBar(viewer, "arena.kit-disabled", "arena", arena.displayName(),
                        "kit", kit.displayName());
                case LAST_ARENA -> plugin.messages().actionBar(viewer, "arena.kit-last-arena");
                case UNKNOWN_ARENA -> plugin.messages().actionBar(viewer, "arena.not-found", "arena", arena.name());
            }
            populate(def);
            return;
        }
        String action = actions.get(slot);
        if (action == null) return;
        switch (action.toUpperCase(Locale.ROOT)) {
            case "RESET" -> {
                plugin.arenas().clearRestriction(kit.id());
                plugin.messages().actionBar(viewer, "arena.kit-reset", "kit", kit.displayName());
                populate(def);
            }
            case "PREVIOUS_PAGE" -> {
                if (page > 0) {
                    page--;
                    populate(def);
                }
            }
            case "NEXT_PAGE" -> {
                if (page < pageCount - 1) {
                    page++;
                    populate(def);
                }
            }
            case "BACK" -> onBack.run();
            case "CLOSE" -> viewer.closeInventory();
            default -> { /* unknown action ids are ignored */ }
        }
    }
}
