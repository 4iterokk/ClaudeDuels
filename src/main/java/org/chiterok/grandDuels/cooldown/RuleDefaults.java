package org.chiterok.grandDuels.cooldown;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Server defaults for every {@link RuleType}, read from {@code cooldown-items.<key>.default-cooldown} and
 * {@code default-banned} in menu/cooldowns.yml. Players override them with their personal preferences.
 */
public record RuleDefaults(Map<RuleType, Double> cooldowns, Set<RuleType> banned) {

    public RuleDefaults {
        cooldowns = Map.copyOf(cooldowns);
        banned = Set.copyOf(banned);
    }

    public static RuleDefaults from(@Nullable ConfigurationSection items) {
        Map<RuleType, Double> cooldowns = new EnumMap<>(RuleType.class);
        Set<RuleType> banned = EnumSet.noneOf(RuleType.class);
        for (RuleType type : RuleType.values()) {
            ConfigurationSection section = items == null ? null : items.getConfigurationSection(type.configKey());
            double seconds = section == null || !type.hasCooldown() ? 0.0
                    : Math.max(0.0, section.getDouble("default-cooldown", 0.0));
            cooldowns.put(type, seconds);
            if (section != null && section.getBoolean("default-banned", false)) banned.add(type);
        }
        return new RuleDefaults(cooldowns, banned);
    }

    public double seconds(RuleType type) {
        return cooldowns.getOrDefault(type, 0.0);
    }

    public boolean isBanned(RuleType type) {
        return banned.contains(type);
    }
}
