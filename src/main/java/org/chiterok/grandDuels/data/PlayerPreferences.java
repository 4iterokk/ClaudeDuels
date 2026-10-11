package org.chiterok.grandDuels.data;

import org.chiterok.grandDuels.cooldown.RuleDefaults;
import org.chiterok.grandDuels.cooldown.RuleType;
import org.chiterok.grandDuels.match.MatchSettings;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * A player's personal duel rules: per-rule cooldown and ban overrides plus the custom-cooldowns switch.
 * Rules without an override use the server default from menu/cooldowns.yml. Main thread only.
 */
public final class PlayerPreferences {

    private final Runnable onChange;
    private boolean customCooldowns = true;
    private final Map<RuleType, Double> cooldownOverrides = new EnumMap<>(RuleType.class);
    private final Map<RuleType, Boolean> banOverrides = new EnumMap<>(RuleType.class);

    PlayerPreferences(Runnable onChange) {
        this.onChange = onChange;
    }

    public boolean customCooldowns() {
        return customCooldowns;
    }

    public void setCustomCooldowns(boolean value) {
        this.customCooldowns = value;
        onChange.run();
    }

    public double cooldownSeconds(RuleType type, double serverDefault) {
        return cooldownOverrides.getOrDefault(type, serverDefault);
    }

    public void setCooldown(RuleType type, double seconds) {
        cooldownOverrides.put(type, Math.max(0.0, seconds));
        onChange.run();
    }

    public boolean isBanned(RuleType type, boolean serverDefault) {
        return banOverrides.getOrDefault(type, serverDefault);
    }

    public void toggleBanned(RuleType type, boolean serverDefault) {
        boolean next = !isBanned(type, serverDefault);
        if (next == serverDefault) banOverrides.remove(type);
        else banOverrides.put(type, next);
        onChange.run();
    }

    public void reset() {
        customCooldowns = true;
        cooldownOverrides.clear();
        banOverrides.clear();
        onChange.run();
    }

    public boolean isDefault() {
        return customCooldowns && cooldownOverrides.isEmpty() && banOverrides.isEmpty();
    }

    Map<RuleType, Double> cooldownOverrides() {
        return cooldownOverrides;
    }

    Map<RuleType, Boolean> banOverrides() {
        return banOverrides;
    }

    void loadCustomCooldowns(boolean value) {
        this.customCooldowns = value;
    }

    /** Resolves these preferences against the server defaults into the rules of a duel. */
    public MatchSettings toSettings(RuleDefaults defaults) {
        Map<RuleType, Double> resolved = new EnumMap<>(RuleType.class);
        Set<RuleType> banned = EnumSet.noneOf(RuleType.class);
        for (RuleType type : RuleType.values()) {
            resolved.put(type, cooldownSeconds(type, defaults.seconds(type)));
            if (isBanned(type, defaults.isBanned(type))) banned.add(type);
        }
        return new MatchSettings(customCooldowns, resolved, banned);
    }
}
