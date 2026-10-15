package org.chiterok.grandDuels.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.chiterok.grandDuels.ClaudeDuels;

import java.util.List;

/**
 * Owns every YAML file of the plugin and the typed {@link Settings} derived from config.yml.
 * <p>
 * Localized files live in {@code lang/<language>/}: {@code messages.yml} and {@code menu/<folder>/<menu>.yml}. The
 * files of every bundled language are generated on first start; {@code language} in config.yml picks the active one.
 * A language folder an admin created by hand falls back to the English defaults for missing keys.
 */
public final class ConfigManager {

    /** Languages that ship inside the jar. */
    public static final List<String> BUNDLED_LANGUAGES = List.of("en", "ru");

    private final ClaudeDuels plugin;
    private final YamlFile config;
    private final YamlFile kits;
    private final YamlFile arenas;
    private volatile YamlFile messages;
    private volatile Settings settings;

    public ConfigManager(ClaudeDuels plugin) {
        this.plugin = plugin;
        this.config = new YamlFile(plugin, "config.yml", YamlFile.Mode.RESOURCE_WITH_DEFAULTS);
        this.kits = new YamlFile(plugin, "kits.yml", YamlFile.Mode.RESOURCE_ONCE);
        this.arenas = new YamlFile(plugin, "arenas.yml", YamlFile.Mode.DATA);
        this.settings = Settings.from(config.get());
        generateBundledMessages();
        this.messages = openMessages();
    }

    /** Reloads config, messages and kits files. Arenas are runtime state and are intentionally not reloaded. */
    public void reload() {
        config.reload();
        kits.reload();
        this.settings = Settings.from(config.get());
        this.messages = openMessages();
    }

    /** Relative path of a localized file for the active language, e.g. {@code lang/en/menu/player/ranked.yml}. */
    public String localizedPath(String relative) {
        return localizedPath(settings.language(), relative);
    }

    public static String localizedPath(String language, String relative) {
        return "lang/" + language + "/" + relative;
    }

    /** Opens (creating it when missing) a localized file of {@code language}; English is the fallback default. */
    public YamlFile openLocalized(String language, String relative, YamlFile.Mode mode) {
        return new YamlFile(plugin, localizedPath(language, relative),
                localizedPath(Settings.DEFAULT_LANGUAGE, relative), mode);
    }

    private void generateBundledMessages() {
        for (String language : BUNDLED_LANGUAGES) openLocalized(language, "messages.yml", YamlFile.Mode.RESOURCE_WITH_DEFAULTS);
    }

    private YamlFile openMessages() {
        return openLocalized(settings.language(), "messages.yml", YamlFile.Mode.RESOURCE_WITH_DEFAULTS);
    }

    public Settings settings() {
        return settings;
    }

    public FileConfiguration config() {
        return config.get();
    }

    public FileConfiguration messages() {
        return messages.get();
    }

    public FileConfiguration kits() {
        return kits.get();
    }

    public YamlFile configFile() {
        return config;
    }

    public YamlFile kitsFile() {
        return kits;
    }

    public YamlFile arenasFile() {
        return arenas;
    }
}
