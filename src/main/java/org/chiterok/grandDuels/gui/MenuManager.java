package org.chiterok.grandDuels.gui;

import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.config.ConfigManager;
import org.chiterok.grandDuels.config.YamlFile;
import org.chiterok.grandDuels.cooldown.RuleDefaults;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads {@code plugins/GrandDuels/lang/<language>/menu/<folder>/<name>.yml}. Menus are split into {@code player/} and
 * {@code admin/} folders. Files are copied from the jar once and are then owned by the server; the menus of every
 * bundled language are generated on first start and the one matching {@code language} in config.yml is used.
 */
public final class MenuManager {

    public static final String KIT_SELECTOR = "player/kit-selector";
    public static final String OWN_KIT_SELECTOR = "player/own-kit-selector";
    public static final String DUEL_SETTINGS = "player/duel-settings";
    public static final String COOLDOWNS = "player/cooldowns";
    public static final String RANKED = "player/ranked";
    public static final String UNRANKED = "player/unranked";
    public static final String KIT_PREVIEW = "player/kit-preview";
    public static final String ARENA_SELECTOR = "player/arena-selector";
    public static final String ARENA_MODE = "player/arena-mode";
    public static final String SPECTATE = "player/spectate";
    public static final String ADMIN_ARENA_KITS = "admin/arena-kits";
    public static final String ADMIN_KIT_ARENAS = "admin/kit-arenas";

    private static final String[] MENUS = {KIT_SELECTOR, OWN_KIT_SELECTOR, KIT_PREVIEW, DUEL_SETTINGS, COOLDOWNS, RANKED, UNRANKED,
            ARENA_SELECTOR, ARENA_MODE, SPECTATE, ADMIN_ARENA_KITS, ADMIN_KIT_ARENAS};

    private final ClaudeDuels plugin;
    private final Map<String, YamlFile> files = new LinkedHashMap<>();
    private volatile Map<String, MenuDefinition> definitions = Map.of();
    private volatile RuleDefaults defaults = RuleDefaults.from(null);

    public MenuManager(ClaudeDuels plugin) {
        this.plugin = plugin;
        generateBundled();
        open();
        parse();
    }

    /** Re-reads the menu files; picks up a changed {@code language}. */
    public void reload() {
        open();
        parse();
    }

    private void generateBundled() {
        for (String language : ConfigManager.BUNDLED_LANGUAGES) {
            for (String id : MENUS) plugin.configs().openLocalized(language, "menu/" + id + ".yml", YamlFile.Mode.RESOURCE_ONCE);
        }
    }

    private void open() {
        files.clear();
        String language = plugin.settings().language();
        for (String id : MENUS) {
            files.put(id, plugin.configs().openLocalized(language, "menu/" + id + ".yml", YamlFile.Mode.RESOURCE_ONCE));
        }
    }

    private void parse() {
        Map<String, MenuDefinition> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, YamlFile> entry : files.entrySet()) {
            parsed.put(entry.getKey(), MenuDefinition.parse(entry.getKey(), entry.getValue().get(), plugin.getLogger()));
        }
        this.definitions = parsed;
        this.defaults = RuleDefaults.from(parsed.get(COOLDOWNS).root().getConfigurationSection("cooldown-items"));
    }

    /** Server default cooldowns/bans, read from menu/player/cooldowns.yml. */
    public RuleDefaults defaults() {
        return defaults;
    }

    public MenuDefinition get(String id) {
        MenuDefinition definition = definitions.get(id);
        if (definition == null) throw new IllegalArgumentException("unknown menu " + id);
        return definition;
    }
}
