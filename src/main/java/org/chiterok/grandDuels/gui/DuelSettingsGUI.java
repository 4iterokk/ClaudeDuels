package org.chiterok.grandDuels.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaGroup;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.cooldown.RuleType;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.match.MatchSettings;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Request screen (menu/player/duel-settings.yml). The toggles start from the sender's personal preferences and only
 * affect this request. The sender can also pick the arena (menu/player/arena-selector.yml).
 */
public final class DuelSettingsGUI extends GuiHolder {

    private final UUID targetId;
    private final String targetName;
    private final Kit kit;
    private final int returnPage;
    private final KitSelectorGUI.Source source;
    private MatchSettings settings;
    private @Nullable String arenaId;
    private Map<Integer, String> actions = Map.of();

    public DuelSettingsGUI(ClaudeDuels plugin, Player viewer, Player target, Kit kit, int returnPage,
                           KitSelectorGUI.Source source) {
        super(plugin, viewer);
        this.targetId = target.getUniqueId();
        this.targetName = target.getName();
        this.kit = kit;
        this.returnPage = returnPage;
        this.source = source;
        this.settings = plugin.preferences().get(viewer.getUniqueId()).toSettings(plugin.menus().defaults());
    }

    @Override
    public void open() {
        MenuDefinition def = plugin.menus().get(MenuManager.DUEL_SETTINGS);
        create(def, placeholders());
        refresh(def);
        viewer.openInventory(getInventory());
    }

    private Map<String, String> placeholders() {
        return Messages.ph("target", targetName, "kit", kit.displayName(), "arena", arenaName());
    }

    /** Display name of the chosen arena, or the "random" text. A chosen arena that vanished counts as random. */
    private String arenaName() {
        ArenaGroup arena = arenaId == null ? null : plugin.arenas().group(arenaId);
        if (arena == null) {
            arenaId = null;
            return plugin.messages().string("duels.arena-random");
        }
        return arena.displayName();
    }

    private boolean gapplesAllowed() {
        return !settings.isBanned(RuleType.GOLDEN_APPLE) && !settings.isBanned(RuleType.ENCHANTED_GOLDEN_APPLE);
    }

    private void refresh(MenuDefinition def) {
        Set<String> flags = new HashSet<>();
        if (settings.customCooldowns()) flags.add("cooldowns");
        if (gapplesAllowed()) flags.add("gapples");
        this.actions = render(def, flags, placeholders()).actions();
    }

    @Override
    public void onClick(int slot, ClickType click) {
        String action = actions.get(slot);
        if (action == null) return;
        MenuDefinition def = plugin.menus().get(MenuManager.DUEL_SETTINGS);
        switch (action.toUpperCase(Locale.ROOT)) {
            case "TOGGLE_COOLDOWNS" -> {
                settings = settings.withCustomCooldowns(!settings.customCooldowns());
                refresh(def);
            }
            case "TOGGLE_GAPPLES" -> {
                Set<RuleType> banned = EnumSet.noneOf(RuleType.class);
                banned.addAll(settings.banned());
                if (gapplesAllowed()) {
                    banned.add(RuleType.GOLDEN_APPLE);
                    banned.add(RuleType.ENCHANTED_GOLDEN_APPLE);
                } else {
                    banned.remove(RuleType.GOLDEN_APPLE);
                    banned.remove(RuleType.ENCHANTED_GOLDEN_APPLE);
                }
                settings = settings.withBanned(banned);
                refresh(def);
            }
            case "SELECT_ARENA" -> new ArenaSelectorGUI(plugin, viewer, kit, arenaId, chosen -> {
                this.arenaId = chosen;
                open();
            }, this::open).open();
            case "OPEN_COOLDOWNS" -> new CooldownMenuGUI(plugin, viewer, () -> {
                // the personal rules may have changed: rebuild the request rules from them, keep the arena choice
                this.settings = plugin.preferences().get(viewer.getUniqueId()).toSettings(plugin.menus().defaults());
                open();
            }).open();
            case "SEND" -> {
                Player target = plugin.getServer().getPlayer(targetId);
                viewer.closeInventory();
                if (target == null) {
                    plugin.messages().send(viewer, "general.player-not-found", "player", targetName);
                    return;
                }
                plugin.duels().sendRequest(viewer, target, kit, settings, arenaId);
            }
            case "BACK" -> {
                Player target = plugin.getServer().getPlayer(targetId);
                if (target == null) {
                    viewer.closeInventory();
                    return;
                }
                new KitSelectorGUI(plugin, viewer, target, returnPage, source).open();
            }
            case "CLOSE" -> viewer.closeInventory();
            default -> { /* unknown action ids are ignored */ }
        }
    }
}
