package org.chiterok.grandDuels.cooldown;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.utils.TimeUtil;

/**
 * Item cooldowns of duels and arena mode, backed entirely by the native item cooldown of the player
 * ({@link Player#setCooldown(Material, int)}): the item is greyed out on the client and the server itself refuses to
 * start using it (eating, throwing, ...). The remaining time is read back from the player, so there is no second
 * source of truth that could go stale (e.g. after a death, a respawn or another plugin resetting the cooldown).
 * All access happens on the main thread.
 */
public final class PvPCooldownManager {

    private static final long TICK_MILLIS = 50L;

    private final ClaudeDuels plugin;

    public PvPCooldownManager(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    public long remainingMillis(Player player, RuleType type) {
        Material material = cooldownMaterial(type);
        if (material == null) return 0L;
        return Math.max(0, player.getCooldown(material)) * TICK_MILLIS;
    }

    public boolean isOnCooldown(Player player, RuleType type) {
        Material material = cooldownMaterial(type);
        return material != null && player.hasCooldown(material);
    }

    /** Starts a cooldown of {@code seconds} (the duel's rule for this item). 0 or less disables it. */
    public void apply(Player player, RuleType type, double seconds) {
        Material material = cooldownMaterial(type);
        if (seconds <= 0.0 || material == null) return;
        player.setCooldown(material, Math.max(1, (int) Math.ceil(seconds * 20.0)));
    }

    /**
     * Same as {@link #apply} one tick later. Items with a vanilla {@code use_cooldown} (ender pearl, wind charge, ...)
     * get their short vanilla cooldown after the launch/consume event has run, which would overwrite a cooldown set
     * inside the event. Applying it on the next tick makes the configured duration win.
     */
    public void applyNextTick(Player player, RuleType type, double seconds) {
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) apply(player, type, seconds);
        });
    }

    public void notifyBlocked(Player player, RuleType type) {
        plugin.messages().actionBar(player, type.messagePath(),
                "time", TimeUtil.seconds(remainingMillis(player, type)));
    }

    /** Clears the item cooldowns of every rule type (when a duel / arena session starts or ends). */
    public void clear(Player player) {
        if (!player.isOnline()) return;
        for (RuleType type : RuleType.values()) {
            Material material = cooldownMaterial(type);
            if (material != null) player.setCooldown(material, 0);
        }
    }

    /** The item that carries the cooldown of {@code type}, or {@code null} for rules without a cooldown. */
    private static Material cooldownMaterial(RuleType type) {
        return type.hasCooldown() ? type.material() : null;
    }
}
