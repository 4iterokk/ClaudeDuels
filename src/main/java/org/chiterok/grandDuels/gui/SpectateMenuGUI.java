package org.chiterok.grandDuels.gui;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.match.Match;
import org.chiterok.grandDuels.match.SpectatorManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /spec} menu (menu/player/spectate.yml): every running duel with live health and time, refreshed every second
 * while the menu is open. Left click spectates the first duelist, right click the second one.
 */
public final class SpectateMenuGUI extends GuiHolder {

    private final Map<Integer, Match> matchBySlot = new HashMap<>();
    private Map<Integer, String> actions = Map.of();
    private BukkitTask refreshTask;
    private int page;
    private int pageCount = 1;

    public SpectateMenuGUI(ClaudeDuels plugin, Player viewer) {
        super(plugin, viewer);
    }

    @Override
    public void open() {
        cancelRefresh();
        MenuDefinition def = plugin.menus().get(MenuManager.SPECTATE);
        create(def, placeholders(activeMatches().size()));
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
        populate(plugin.menus().get(MenuManager.SPECTATE));
    }

    private void cancelRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    /** Duels that can still be watched, in the order they started. */
    private List<Match> activeMatches() {
        List<Match> list = new ArrayList<>();
        for (Match match : plugin.matches().all()) {
            if (match.phase() != Match.Phase.FINISHED) list.add(match);
        }
        return list;
    }

    private Map<String, String> placeholders(int totalDuels) {
        Match watched = plugin.spectators().matchOf(viewer.getUniqueId());
        String watching = "-";
        if (watched != null) {
            Map<String, String> values = plugin.spectators().describe(watched);
            watching = values.get("player1") + " vs " + values.get("player2");
        }
        return Messages.ph("page", page + 1, "pages", pageCount, "total_duels", totalDuels,
                "total_spectators", plugin.spectators().count(), "watching", watching);
    }

    private void populate(MenuDefinition def) {
        List<Match> matches = activeMatches();
        List<Integer> matchSlots = def.slots("match-slots");
        if (matchSlots.isEmpty()) {
            for (int i = 0; i < Math.max(1, def.size() - 9); i++) matchSlots.add(i);
        }
        int perPage = matchSlots.size();
        pageCount = Math.max(1, (int) Math.ceil(matches.size() / (double) perPage));
        page = Math.max(0, Math.min(page, pageCount - 1));

        Set<String> flags = new HashSet<>();
        if (page > 0) flags.add("has-previous");
        if (page < pageCount - 1) flags.add("has-next");
        if (matches.isEmpty()) flags.add("no-duels");
        if (plugin.spectators().isSpectating(viewer.getUniqueId())) flags.add("spectating");

        Map<String, String> ph = placeholders(matches.size());
        Rendered rendered = render(def, flags, ph);
        this.actions = rendered.actions();

        Match watched = plugin.spectators().matchOf(viewer.getUniqueId());
        matchBySlot.clear();
        for (int i = 0; i < perPage; i++) {
            int slot = matchSlots.get(i);
            int index = page * perPage + i;
            if (index < matches.size()) {
                Match match = matches.get(index);
                getInventory().setItem(slot, matchItem(def, match, match == watched, ph));
                matchBySlot.put(slot, match);
                actions.remove(slot);
            } else if (!rendered.occupied().contains(slot)) {
                getInventory().setItem(slot, null);
            }
        }
    }

    private ItemStack matchItem(MenuDefinition def, Match match, boolean watched, Map<String, String> ph) {
        ConfigurationSection template = def.root().getConfigurationSection("match-item");
        String name = template == null ? "&f{player1} &7vs &f{player2}" : template.getString("name", "&f{player1} &7vs &f{player2}");
        List<String> lore = template == null ? List.of("&7Kit: &f{kit}", "&7Time: &f{time}")
                : MenuItem.loreOf(template, watched && template.contains("lore-watching") ? "lore-watching" : "lore");
        boolean enchanted = template != null && watched && template.getBoolean("enchanted-when-watching", true);

        Map<String, String> local = new HashMap<>(ph);
        local.putAll(plugin.spectators().describe(match));

        ItemStack icon = match.kit().icon().clone();
        icon.setAmount(1);
        icon.editMeta(meta -> meta.lore(null));
        return MenuItem.decorate(icon, name, lore, enchanted, local, viewer);
    }

    @Override
    public void onClick(int slot, ClickType click) {
        Match match = matchBySlot.get(slot);
        if (match != null) {
            UUID focus = match.participants().get(click.isRightClick() ? 1 : 0);
            SpectatorManager.JoinResult result = plugin.spectators().watch(viewer, match, focus);
            if (result.success()) {
                viewer.closeInventory();
            } else if (result == SpectatorManager.JoinResult.MATCH_ENDED) {
                open(); // the list is stale: rebuild it without the finished duel
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
            case "LEAVE" -> {
                if (plugin.spectators().leave(viewer) == SpectatorManager.LeaveResult.LEFT) {
                    plugin.messages().send(viewer, "spectate.left");
                }
                viewer.closeInventory();
            }
            case "CLOSE" -> viewer.closeInventory();
            default -> { /* unknown action ids are ignored */ }
        }
    }

    @Override
    public void onClose() {
        cancelRefresh();
    }
}
