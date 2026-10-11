package org.chiterok.grandDuels.cooldown;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Nullable;

/**
 * Everything a player can restrict or put on cooldown for a duel (menu/cooldowns.yml).
 * Cooldown types can be banned and given a cooldown; ban-only types (elytra, riptide) can only be banned.
 */
public enum RuleType {
    GOLDEN_APPLE("golden-apple", Material.GOLDEN_APPLE, true),
    ENCHANTED_GOLDEN_APPLE("enchanted-golden-apple", Material.ENCHANTED_GOLDEN_APPLE, true),
    TRIDENT("trident", Material.TRIDENT, true),
    WIND_CHARGE("wind-charge", Material.WIND_CHARGE, true),
    ENDER_PEARL("ender-pearl", Material.ENDER_PEARL, true),
    CHORUS_FRUIT("chorus-fruit", Material.CHORUS_FRUIT, true),
    FIREWORK_ROCKET("firework-rocket", Material.FIREWORK_ROCKET, true),
    ELYTRA("elytra", Material.ELYTRA, false),
    RIPTIDE("riptide", null, false);

    private final String configKey;
    private final @Nullable Material material;
    private final boolean hasCooldown;

    RuleType(String configKey, @Nullable Material material, boolean hasCooldown) {
        this.configKey = configKey;
        this.material = material;
        this.hasCooldown = hasCooldown;
    }

    /** Key used in menu/cooldowns.yml, messages.yml and preferences.yml. */
    public String configKey() {
        return configKey;
    }

    public String messagePath() {
        return "cooldowns." + configKey;
    }

    /** The item this rule is about (shown on cooldown, stripped from kits when banned); null for pure abilities. */
    public @Nullable Material material() {
        return material;
    }

    public boolean hasCooldown() {
        return hasCooldown;
    }

    /** Resolves a config key ({@code ender-pearl}) or enum name ({@code ENDER_PEARL}); {@code null} when unknown. */
    public static @Nullable RuleType fromKey(@Nullable String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        for (RuleType type : values()) {
            if (type.configKey.equals(normalized)) return type;
        }
        return null;
    }

    public static @Nullable RuleType forConsumable(Material material) {
        return switch (material) {
            case GOLDEN_APPLE -> GOLDEN_APPLE;
            case ENCHANTED_GOLDEN_APPLE -> ENCHANTED_GOLDEN_APPLE;
            case CHORUS_FRUIT -> CHORUS_FRUIT;
            default -> null;
        };
    }

    public static @Nullable RuleType forProjectile(EntityType type) {
        return switch (type) {
            case ENDER_PEARL -> ENDER_PEARL;
            case TRIDENT -> TRIDENT;
            case WIND_CHARGE -> WIND_CHARGE;
            default -> null;
        };
    }
}
