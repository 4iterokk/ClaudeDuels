package org.chiterok.grandDuels.gui;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.match.DuelMode;
import org.chiterok.grandDuels.match.QueueManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * /ranked and /unranked menu (menu/ranked.yml, menu/unranked.yml): standard kits only, with live counts of players
 * in duels and in the queue that refresh every second while the menu is open. Clicking a kit joins/leaves its queue.
 */
public final class QueueMenuGUI extends GuiHolder {

    private final DuelMode mode;
    private final String menuId;
    private final List<Kit> kits;
    private final Map<Integer, Kit> kitBySlot = new HashMap<>();
    private Map<Integer, String> actions = Map.of();
    private BukkitTask refreshTask;
    private int page;
    private int pageCount = 1;

    public QueueMenuGUI(GrandDuels plugin, Player viewer, DuelMode mode) {
        super(plugin, viewer);
        this.mode = mode;
        this.menuId = mode == DuelMode.RANKED ? MenuManager.RANKED : MenuManager.UNRANKED;
        this.kits = new ArrayList<>(plugin.kits().queueKits(mode)); // standard kits enabled for this mode only; player kits never appear here
    }

    @Override
    public void open() {
        cancelRefresh();
        MenuDefinition def = plugin.menus().get(menuId);
        create(def, placeholders());
        populate(def);
        viewer.openInventory(getInventory());
        // started after openInventory: opening closes the previous inventory, which cancels any older task
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, 20L);
    }

    private void refresh() {
        if (!viewer.isOnline() || viewer.getOpenInventory().getTopInventory().getHolder() != this) {
            cancelRefresh();
            return;
        }
        populate(plugin.menus().get(menuId));
    }

    private void cancelRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private Map<String, String> placeholders() {
        QueueManager.Key queued = plugin.queues().queueOf(viewer.getUniqueId());
        String queuedKit = "-";
        if (queued != null && queued.mode() == mode) {
            Kit kit = plugin.kits().get(queued.kitId());
            queuedKit = kit == null ? queued.kitId() : kit.displayName();
        }
        return Messages.ph("page", page + 1, "pages", pageCount,
                "mode", plugin.messages().string("modes." + mode.name().toLowerCase(Locale.ROOT)),
                "total_in_duels", plugin.matches().playersInMode(mode),
                "total_in_queue", plugin.queues().waitingInMode(mode),
                "queued_kit", queuedKit,
                "elo", plugin.queues().overallRating(viewer));
    }

    private void populate(MenuDefinition def) {
        List<Integer> kitSlots = def.slots("kit-slots");
        if (kitSlots.isEmpty()) {
            for (int i = 0; i < Math.max(1, def.size() - 9); i++) kitSlots.add(i);
        }
        int perPage = kitSlots.size();
        pageCount = Math.max(1, (int) Math.ceil(kits.size() / (double) perPage));
        page = Math.min(page, pageCount - 1);

        QueueManager.Key queued = plugin.queues().queueOf(viewer.getUniqueId());
        boolean inThisMode = queued != null && queued.mode() == mode;
        Set<String> flags = new HashSet<>();
        if (page > 0) flags.add("has-previous");
        if (page < pageCount - 1) flags.add("has-next");
        if (kits.isEmpty()) flags.add("no-kits");
        if (inThisMode) flags.add("in-queue");

        Map<String, String> ph = placeholders();
        Rendered rendered = render(def, flags, ph);
        this.actions = rendered.actions();

        Map<String, Integer> inDuels = plugin.matches().playersPerKit(mode);
        Map<String, Integer> waiting = plugin.queues().waitingPerKit(mode);
        kitBySlot.clear();
        for (int i = 0; i < perPage; i++) {
            int slot = kitSlots.get(i);
            int index = page * perPage + i;
            if (index < kits.size()) {
                Kit kit = kits.get(index);
                boolean queuedHere = inThisMode && queued.kitId().equals(kit.id());
                getInventory().setItem(slot, kitItem(def, kit, queuedHere, inDuels.getOrDefault(kit.id(), 0),
                        waiting.getOrDefault(kit.id(), 0), ph));
                kitBySlot.put(slot, kit);
                actions.remove(slot);
            } else if (!rendered.occupied().contains(slot)) {
                getInventory().setItem(slot, null);
            }
        }
    }

    private ItemStack kitItem(MenuDefinition def, Kit kit, boolean queuedHere, int inDuel, int inQueue,
                              Map<String, String> ph) {
        ConfigurationSection template = def.root().getConfigurationSection("kit-item");
        String name = template == null ? "{kit_name}" : template.getString("name", "{kit_name}");
        List<String> loreTemplate = template == null ? List.of("{kit_description}", "&7In duels: &f{in_duel}")
                : MenuItem.loreOf(template, queuedHere && template.contains("lore-queued") ? "lore-queued" : "lore");
        boolean enchanted = template != null && queuedHere && template.getBoolean("enchanted-when-queued", true);

        List<String> lore = new ArrayList<>();
        for (String line : loreTemplate) {
            if (line.trim().equals("{kit_description}")) lore.addAll(kit.description());
            else lore.add(line);
        }
        Map<String, String> local = new HashMap<>(ph);
        local.put("kit_name", kit.displayName());
        local.put("kit", kit.displayName());
        local.put("in_duel", String.valueOf(inDuel));
        local.put("in_queue", String.valueOf(inQueue));

        ItemStack icon = kit.icon().clone();
        icon.setAmount(1);
        icon.editMeta(meta -> meta.lore(null));
        return MenuItem.decorate(icon, name, lore, enchanted, local, viewer);
    }

    @Override
    public void onClick(int slot, ClickType click) {
        Kit kit = kitBySlot.get(slot);
        if (kit != null && click.isRightClick()) {
            new KitPreviewGUI(plugin, viewer, kit, this::open).open();
            return;
        }
        if (kit != null) {
            toggleQueue(kit);
            populate(plugin.menus().get(menuId));
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
            case "LEAVE_QUEUE" -> {
                if (plugin.queues().leave(viewer.getUniqueId())) plugin.messages().send(viewer, "queue.left");
                populate(plugin.menus().get(menuId));
            }
            case "CLOSE" -> viewer.closeInventory();
            default -> { /* unknown action ids are ignored */ }
        }
    }

    private void toggleQueue(Kit kit) {
        QueueManager.Key queued = plugin.queues().queueOf(viewer.getUniqueId());
        String modeName = plugin.messages().string("modes." + mode.name().toLowerCase(Locale.ROOT));
        if (queued != null && queued.mode() == mode && queued.kitId().equals(kit.id())) {
            plugin.queues().leave(viewer.getUniqueId());
            plugin.messages().send(viewer, "queue.left");
            return;
        }
        switch (plugin.queues().join(viewer, mode, kit)) {
            case QUEUED, SWITCHED -> {
                if (plugin.matches().isInMatch(viewer.getUniqueId())) {
                    viewer.closeInventory(); // matched immediately
                } else {
                    if (mode == DuelMode.RANKED) {
                        plugin.messages().send(viewer, "queue.joined-ranked", "kit", kit.displayName(),
                                "elo", plugin.queues().ratingOf(viewer, kit.id()),
                                "range", plugin.settings().matchmaking().initialEloRange());
                    } else {
                        plugin.messages().send(viewer, "queue.joined", "mode", modeName, "kit", kit.displayName());
                    }
                }
            }
            case ALREADY_QUEUED -> { /* handled above */ }
            case BUSY -> plugin.messages().send(viewer, "queue.busy");
            case NOT_STANDARD_KIT -> plugin.messages().send(viewer, "queue.standard-only");
            case KIT_DISABLED -> {
                plugin.messages().send(viewer, "queue.kit-disabled");
                open(); // the list is stale: rebuild it without the disabled kit
            }
        }
    }

    @Override
    public void onClose() {
        cancelRefresh();
    }
}
