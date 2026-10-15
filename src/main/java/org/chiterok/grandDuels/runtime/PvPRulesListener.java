package org.chiterok.grandDuels.runtime;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.config.Settings;
import org.chiterok.grandDuels.cooldown.RuleType;
import org.chiterok.grandDuels.match.Match;
import org.chiterok.grandDuels.match.MatchSettings;
import org.jetbrains.annotations.Nullable;

/** Stateful duel rules: item cooldowns, gapple toggle, riptide restriction and heavy-weapon tuning. */
public final class PvPRulesListener implements Listener {

    private static final float MACE_SMASH_MIN_FALL = 1.5f;

    private final ClaudeDuels plugin;

    public PvPRulesListener(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    /**
     * The rules the player is currently bound to: those of their duel, or the server defaults in arena mode.
     * {@code null} when neither applies (the player is not restricted at all).
     */
    private @Nullable MatchSettings rulesOf(Player player) {
        Match match = plugin.matches().of(player);
        return match != null ? match.settings() : plugin.arenaMode().rulesOf(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        MatchSettings rules = rulesOf(player);
        if (rules == null) return;
        RuleType type = RuleType.forConsumable(event.getItem().getType());
        if (type == null || denyIfBanned(player, rules, type, event)) return;
        enforceCooldown(player, rules, type, event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        MatchSettings rules = rulesOf(player);
        if (rules == null) return;
        RuleType type = RuleType.forProjectile(event.getEntityType());
        if (type == null || denyIfBanned(player, rules, type, event)) return;
        enforceCooldown(player, rules, type, event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRiptide(PlayerRiptideEvent event) {
        Player player = event.getPlayer();
        MatchSettings rules = rulesOf(player);
        if (rules != null && rules.customCooldowns()) {
            plugin.cooldowns().apply(player, RuleType.TRIDENT, rules.cooldownSeconds(RuleType.TRIDENT));
        }
    }

    /** @return true if the item is banned in this duel (the event is cancelled and the player informed). */
    private boolean denyIfBanned(Player player, MatchSettings rules, RuleType type, Cancellable event) {
        if (!rules.isBanned(type)) return false;
        event.setCancelled(true);
        plugin.messages().actionBar(player, "duels.item-banned");
        return true;
    }

    /** Cancels the use while on cooldown, otherwise starts the cooldown of this item. */
    private void enforceCooldown(Player player, MatchSettings rules, RuleType type, Cancellable event) {
        if (!rules.customCooldowns()) return;
        if (plugin.cooldowns().isOnCooldown(player, type)) {
            event.setCancelled(true);
            plugin.cooldowns().notifyBlocked(player, type);
            return;
        }
        plugin.cooldowns().applyNextTick(player, type, rules.cooldownSeconds(type));
    }

    /**
     * Right-click handling: a Riptide trident would launch the player (RIPTIDE ban), and fireworks used on the ground
     * are covered by the FIREWORK_ROCKET ban/cooldown. (Gliding boosts are handled by {@link #onElytraBoost}.)
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        MatchSettings rules = rulesOf(player);
        ItemStack item = event.getItem();
        if (rules == null || item == null) return;

        if (item.getType() == Material.TRIDENT && item.containsEnchantment(Enchantment.RIPTIDE)
                && rules.isBanned(RuleType.RIPTIDE)) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setCancelled(true);
            plugin.messages().actionBar(player, "duels.riptide-blocked");
            return;
        }
        if (item.getType() == Material.FIREWORK_ROCKET) {
            if (rules.isBanned(RuleType.FIREWORK_ROCKET)) {
                event.setUseItemInHand(Event.Result.DENY);
                event.setCancelled(true);
                plugin.messages().actionBar(player, "duels.item-banned");
            } else if (action == Action.RIGHT_CLICK_BLOCK && !player.isGliding()) {
                enforceCooldown(player, rules, RuleType.FIREWORK_ROCKET, event);
            }
        }
    }

    /** Elytra boost with a firework: banned or on cooldown -> cancelled, otherwise starts the cooldown. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        Player player = event.getPlayer();
        MatchSettings rules = rulesOf(player);
        if (rules == null || denyIfBanned(player, rules, RuleType.FIREWORK_ROCKET, event)) return;
        enforceCooldown(player, rules, RuleType.FIREWORK_ROCKET, event);
    }

    /** Mace smash and spear charge tuning: optional damage cap and knockback multiplier. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHeavyAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)) return;
        Match match = plugin.matches().of(attacker);
        boolean fighting = match != null ? match.isFighting() : plugin.arenaMode().isIn(attacker.getUniqueId());
        if (!fighting) return;

        Material held = attacker.getInventory().getItemInMainHand().getType();
        Settings.WeaponRule rule;
        if (held == Material.MACE && attacker.getFallDistance() > MACE_SMASH_MIN_FALL) {
            rule = plugin.settings().rules().mace();
        } else if (held.name().endsWith("_SPEAR") && attacker.isSprinting()) {
            rule = plugin.settings().rules().spear();
        } else {
            return;
        }

        double finalDamage = event.getFinalDamage();
        if (rule.maxDamage() > 0.0 && finalDamage > rule.maxDamage()) {
            // Armor/enchant reductions are applied on top of the base damage, so scale the base proportionally.
            event.setDamage(event.getDamage() * (rule.maxDamage() / finalDamage));
        }
        double multiplier = rule.knockbackMultiplier();
        if (Math.abs(multiplier - 1.0) > 1.0E-6) scaleKnockback(victim, multiplier);
    }

    private void scaleKnockback(LivingEntity victim, double multiplier) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (victim.isValid()) victim.setVelocity(victim.getVelocity().multiply(multiplier));
        });
    }
}
