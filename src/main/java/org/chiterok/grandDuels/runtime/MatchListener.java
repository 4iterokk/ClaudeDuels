package org.chiterok.grandDuels.runtime;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.match.Match;
import org.jetbrains.annotations.Nullable;

/** Match flow events: damage rules, death, respawn, quit and crash recovery on join. */
public final class MatchListener implements Listener {

    private final ClaudeDuels plugin;

    public MatchListener(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    /** Duelists are invulnerable outside the FIGHTING phase (countdown, celebration). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Match match = plugin.matches().of(player);
        if (match != null && !match.isFighting()) event.setCancelled(true);
    }

    /** Only the two duelists may damage each other. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        Match victimMatch = plugin.matches().of(victim);
        Match attackerMatch = attacker == null ? null : plugin.matches().of(attacker);
        if (victimMatch == null && attackerMatch == null) return;
        if (attacker == null) return; // environment / mobs are not restricted
        if (attacker.getUniqueId().equals(victim.getUniqueId())) return; // self damage (wind charge, pearls)
        if (victimMatch == null || victimMatch != attackerMatch) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        Match match = plugin.matches().of(attacker);
        if (match != null && match == plugin.matches().of(victim)) {
            match.addDamage(attacker.getUniqueId(), event.getFinalDamage());
        }
    }

    private static @Nullable Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Match match = plugin.matches().of(victim);
        if (match == null) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        match.handleDeath(victim);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Match match = plugin.matches().of(event.getPlayer());
        if (match == null) return;
        Location spawn = match.spawnOf(event.getPlayer().getUniqueId());
        if (spawn != null) event.setRespawnLocation(spawn);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.requests().purge(player.getUniqueId());
        plugin.queues().leave(player.getUniqueId());
        plugin.playerKits().evict(player.getUniqueId());
        plugin.kitEdits().handleQuit(player);
        plugin.arenaMode().handleQuit(player);
        plugin.preferences().save();
        Match match = plugin.matches().of(player);
        if (match != null) match.handleQuit(player);
        plugin.stats().evict(player.getUniqueId());
        plugin.kitRatings().evict(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.stats().preload(player);
        plugin.kitRatings().preload(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && plugin.matches().recover(player)) {
                plugin.messages().send(player, "duels.recovered");
            }
        }, 2L);
    }
}
