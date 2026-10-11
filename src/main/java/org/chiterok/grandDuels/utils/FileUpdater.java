package org.chiterok.grandDuels.utils;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Brings a file on disk up to date with the defaults bundled in the jar: every key that exists in the defaults but is
 * missing on disk (a new message, a new option of a plugin update) is added together with its comments. Keys that are
 * already there are never touched - including values an admin changed and lists - and nothing is ever removed.
 * <p>
 * Used for config.yml and messages.yml; menus and kits are owned by the admin and are deliberately not updated.
 */
public final class FileUpdater {

    private FileUpdater() {
    }

    /**
     * Adds the missing defaults to {@code loaded} and saves it to {@code file} when something was added.
     * @return the dot-separated paths that were added (empty when the file was already up to date)
     */
    public static List<String> update(File file, YamlConfiguration loaded, YamlConfiguration defaults, Logger logger) {
        Set<String> sectionsBefore = new HashSet<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) && loaded.isConfigurationSection(key)) sectionsBefore.add(key);
        }

        List<String> added = new java.util.ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || loaded.isSet(key)) continue;
            loaded.set(key, defaults.get(key));
            loaded.setComments(key, defaults.getComments(key));
            loaded.setInlineComments(key, defaults.getInlineComments(key));
            added.add(key);
        }
        if (added.isEmpty()) return added;

        // comments above sections that were created by the update (existing sections keep what the admin has)
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) && !sectionsBefore.contains(key)) {
                loaded.setComments(key, defaults.getComments(key));
            }
        }
        try {
            loaded.save(file);
            logger.info("Added " + added.size() + " missing default key(s) to " + file.getName());
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not update " + file.getName(), e);
        }
        return added;
    }
}
