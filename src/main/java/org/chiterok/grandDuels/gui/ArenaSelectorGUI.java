package org.chiterok.grandDuels.gui;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaGroup;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.kit.Kit;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Arena chooser of a duel request (menu/player/arena-selector.yml). Lists the arena groups the kit may be played on; the
 * request keeps the choice until it is accepted. A group whose arenas are all occupied is shown but cannot be picked;
 * the free sub-arena of a picked group is chosen when the duel starts.
 */
public final class ArenaSelectorGUI extends GuiHolder {

    private final Kit kit;
    private final @Nullable String current;
    private final Consumer<@Nullable String> onChoose;
    private final Runnable onBack;
    private final List<ArenaGroup> arenas;
    private final Map<Integer, ArenaGroup> arenaBySlot = new HashMap<>();
    private Map<Integer, String> actions = Map.of();
    private int page;
    private int pageCount = 1;

    /**
     * @param current  id of the currently chosen arena, {@code null} = random
     * @param onChoose called with the picked arena id, or {@code null} for "random"
     * @param onBack   called when the viewer goes back without picking
     */
    public ArenaSelectorGUI(ClaudeDuels plugin, Player viewer, Kit kit, @Nullable String current,
                            Consumer<@Nullable String> onChoose, Runnable onBack) {
        super(plugin, viewer);
        this.kit = kit;
        this.current = current;
        this.onChoose = onChoose;
        this.onBack = onBack;
        this.arenas = new ArrayList<>(plugin.arenas().availableFor(kit));
    }

    @Override
    public void open() {
        MenuDefinition def = plugin.menus().get(MenuManager.ARENA_SELECTOR);
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
        if (current == null) flags.add("random-selected");
        ArenaGroup selected = current == null ? null : plugin.arenas().group(current);
        Map<String, String> ph = Messages.ph("page", page + 1, "pages", pageCount, "kit", kit.displayName(),
                "selected", selected == null ? plugin.messages().string("duels.arena-random") : selected.displayName());

        create(def, ph);
        Rendered rendered = render(def, flags, ph);
        this.actions = rendered.actions();

        arenaBySlot.clear();
        for (int i = 0; i < perPage; i++) {
            int slot = slots.get(i);
            int index = page * perPage + i;
            if (index < arenas.size()) {
                ArenaGroup arena = arenas.get(index);
                getInventory().setItem(slot, arenaItem(def, arena, ph));
                arenaBySlot.put(slot, arena);
                actions.remove(slot);
            } else if (!rendered.occupied().contains(slot)) {
                getInventory().setItem(slot, null);
            }
        }
        viewer.openInventory(getInventory());
    }

    private ItemStack arenaItem(MenuDefinition def, ArenaGroup arena, Map<String, String> ph) {
        ConfigurationSection template = def.root().getConfigurationSection("arena-item");
        boolean busy = arena.isBusy();
        boolean chosen = arena.id().equalsIgnoreCase(current);

        String loreKey = chosen && template != null && template.contains("lore-selected") ? "lore-selected"
                : busy && template != null && template.contains("lore-busy") ? "lore-busy" : "lore";
        String materialKey = chosen ? "material-selected" : busy ? "material-busy" : "material";
        Material material = Material.GRASS_BLOCK;
        if (template != null) {
            Material parsed = Material.matchMaterial(template.getString(materialKey, template.getString("material", "GRASS_BLOCK")));
            if (parsed != null && parsed.isItem() && !parsed.isAir()) material = parsed;
        }
        // an icon chosen by the admin (/duels arena group seticon) wins; busy/selected are still shown by lore and glint
        Material custom = arena.displayMaterial();
        if (custom != null) material = custom;
        String name = template == null ? "{arena_name}" : template.getString("name", "{arena_name}");
        List<String> lore = template == null ? List.of("{state}") : MenuItem.loreOf(template, loreKey);
        boolean glint = chosen && (template == null || template.getBoolean("enchanted-when-selected", true));

        Map<String, String> local = new HashMap<>(ph);
        local.put("arena_name", arena.displayName());
        local.put("arena_id", arena.id());
        local.put("free", String.valueOf(arena.freeArenas().size()));
        local.put("total", String.valueOf(arena.usableArenas().size()));
        local.put("state", def.root().getString("states." + (busy ? "busy" : "free"), busy ? "busy" : "free"));
        return MenuItem.decorate(new ItemStack(material), name, lore, glint, local, viewer);
    }

    @Override
    public void onClick(int slot, ClickType click) {
        ArenaGroup arena = arenaBySlot.get(slot);
        if (arena != null) {
            if (arena.isBusy()) {
                plugin.messages().send(viewer, "arena.in-use", "arena", arena.displayName());
                return;
            }
            onChoose.accept(arena.id());
            return;
        }
        String action = actions.get(slot);
        if (action == null) return;
        switch (action.toUpperCase(Locale.ROOT)) {
            case "RANDOM_ARENA" -> onChoose.accept(null);
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
            case "BACK" -> onBack.run();
            case "CLOSE" -> viewer.closeInventory();
            default -> { /* unknown action ids are ignored */ }
        }
    }
}
