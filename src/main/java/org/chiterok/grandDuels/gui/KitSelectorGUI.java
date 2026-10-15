package org.chiterok.grandDuels.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.kit.Kit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Paginated kit chooser; layout and texts come from menu/kit-selector.yml. */
public final class KitSelectorGUI extends GuiHolder {

    private final UUID targetId;
    private final String targetName;
    private final List<Kit> kits;
    private final Map<Integer, Kit> kitBySlot = new HashMap<>();
    private Map<Integer, String> actions = Map.of();
    private int page;
    private int pageCount = 1;

    /** Which kits the selector lists: the server's standard kits or the viewer's own kits. */
    public enum Source {
        STANDARD(MenuManager.KIT_SELECTOR),
        OWN(MenuManager.OWN_KIT_SELECTOR);

        final String menuId;

        Source(String menuId) {
            this.menuId = menuId;
        }
    }

    private final Source source;

    public KitSelectorGUI(ClaudeDuels plugin, Player viewer, Player target, int page) {
        this(plugin, viewer, target, page, Source.STANDARD);
    }

    public KitSelectorGUI(ClaudeDuels plugin, Player viewer, Player target, int page, Source source) {
        super(plugin, viewer);
        this.source = source;
        this.targetId = target.getUniqueId();
        this.targetName = target.getName();
        this.kits = source == Source.OWN ? new ArrayList<>(plugin.playerKits().list(viewer.getUniqueId()))
                : new ArrayList<>(plugin.kits().all());
        this.page = Math.max(0, page);
    }

    @Override
    public void open() {
        MenuDefinition def = plugin.menus().get(source.menuId);
        List<Integer> kitSlots = def.slots("kit-slots");
        if (kitSlots.isEmpty()) {
            for (int i = 0; i < Math.max(1, def.size() - 9); i++) kitSlots.add(i);
        }
        int perPage = kitSlots.size();
        pageCount = Math.max(1, (int) Math.ceil(kits.size() / (double) perPage));
        page = Math.min(page, pageCount - 1);

        Set<String> flags = new HashSet<>();
        if (page > 0) flags.add("has-previous");
        if (page < pageCount - 1) flags.add("has-next");
        if (kits.isEmpty()) flags.add("no-kits");
        Map<String, String> ph = Messages.ph("page", page + 1, "pages", pageCount, "target", targetName);

        create(def, ph);
        Rendered rendered = render(def, flags, ph);
        this.actions = rendered.actions();

        kitBySlot.clear();
        for (int i = 0; i < perPage; i++) {
            int slot = kitSlots.get(i);
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
        List<String> loreTemplate = template == null ? List.of("{kit_description}", "", "{kit_features}")
                : MenuItem.loreOf(template, "lore");
        boolean enchanted = template != null && template.getBoolean("enchanted", false);

        List<String> features = new ArrayList<>();
        for (String key : kit.featureKeys()) {
            features.add(def.root().getString("tags." + key.toLowerCase(Locale.ROOT), ""));
        }
        if (!kit.components().isEmpty()) {
            features.add(def.root().getString("tags.components", "")
                    .replace("{components}", String.join(", ", kit.components())));
        }
        features.removeIf(String::isEmpty);

        List<String> lore = new ArrayList<>();
        for (String line : loreTemplate) {
            switch (line.trim()) {
                case "{kit_description}" -> lore.addAll(kit.description());
                case "{kit_features}" -> lore.addAll(features);
                default -> lore.add(line);
            }
        }
        Map<String, String> local = new HashMap<>(ph);
        local.put("kit_name", kit.displayName());
        local.put("kit", kit.displayName());

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
            Player target = plugin.getServer().getPlayer(targetId);
            if (target == null) {
                plugin.messages().send(viewer, "general.player-not-found", "player", targetName);
                viewer.closeInventory();
                return;
            }
            new DuelSettingsGUI(plugin, viewer, target, kit, page, source).open();
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
            case "OPEN_COOLDOWNS" -> {
                int returnPage = page;
                new CooldownMenuGUI(plugin, viewer, () -> {
                    Player target = plugin.getServer().getPlayer(targetId);
                    if (target != null) new KitSelectorGUI(plugin, viewer, target, returnPage, source).open();
                }).open();
            }
            case "OPEN_OWN_KITS" -> {
                Player target = plugin.getServer().getPlayer(targetId);
                if (target != null) new KitSelectorGUI(plugin, viewer, target, 0, Source.OWN).open();
            }
            case "OPEN_STANDARD_KITS" -> {
                Player target = plugin.getServer().getPlayer(targetId);
                if (target != null) new KitSelectorGUI(plugin, viewer, target, 0, Source.STANDARD).open();
            }
            case "CLOSE" -> viewer.closeInventory();
            default -> { /* unknown action ids are ignored */ }
        }
    }
}
