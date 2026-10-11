package org.chiterok.grandDuels.runtime;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.cooldown.RuleType;
import org.chiterok.grandDuels.match.MatchSettings;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * Rules of arena mode ({@code /arena}): players of the arena fight only each other, cannot change blocks, cannot
 * leave by command or teleport, stay inside the configured radius, and are returned to the lobby with their own
 * inventory when they die. Item cooldowns and bans come from {@link PvPRulesListener}.
 */
public final class ArenaModeListener implements Listener {

    private static final String BYPASS_PERMISSION = "grandduels.bypass.commands";
    private static final Set<PlayerTeleportEvent.TeleportCause> BLOCKED_TELEPORTS = Set.of(
            PlayerTeleportEvent.TeleportCause.COMMAND, PlayerTeleportEvent.TeleportCause.NETHER_PORTAL,
            PlayerTeleportEvent.TeleportCause.END_PORTAL, PlayerTeleportEvent.TeleportCause.END_GATEWAY,
            PlayerTeleportEvent.TeleportCause.SPECTATE);

    private final GrandDuels plugin;

    public ArenaModeListener(GrandDuels plugin) {
        this.plugin = plugin;
    }

    private boolean inArena(@Nullable Entity entity) {
        return entity instanceof Player player && plugin.arenaMode().isIn(player.getUniqueId());
    }

    private static @Nullable Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    // ---------------------------------------------------------------- damage and death

    /** A participant whose death is being processed cannot die twice. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && plugin.arenaMode().isDying(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Players inside and outside the arena cannot hurt each other. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (inArena(attacker) != inArena(victim)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackCombat(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (inArena(attacker) && inArena(victim)) plugin.arenaMode().tagCombat(attacker.getUniqueId(), victim.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!inArena(victim)) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        Player killer = victim.getKiller();
        if (killer != null && inArena(killer) && !killer.equals(victim)) {
            plugin.messages().send(killer, "arenamode.kill", "player", victim.getName());
        }
        plugin.messages().send(victim, "arenamode.died");
        plugin.arenaMode().handleDeath(victim);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Location destination = plugin.arenaMode().respawnLocation(event.getPlayer());
        if (destination != null) event.setRespawnLocation(destination);
    }

    // ---------------------------------------------------------------- the world

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (inArena(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (inArena(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (inArena(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (inArena(event.getPlayer())) event.setCancelled(true);
    }

    // ---------------------------------------------------------------- items, abilities, commands

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.settings().rules().blockItemDrop() || !inArena(event.getPlayer())) return;
        event.setCancelled(true);
        plugin.messages().actionBar(event.getPlayer(), "duels.item-blocked");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (plugin.settings().rules().blockItemPickup() && inArena(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding() || !(event.getEntity() instanceof Player player)) return;
        MatchSettings rules = plugin.arenaMode().rulesOf(player.getUniqueId());
        if (rules == null || !rules.isBanned(RuleType.ELYTRA)) return;
        event.setCancelled(true);
        plugin.messages().actionBar(player, "duels.elytra-blocked");
    }

    /** Commands could teleport the player away with the arena kit: only the duel whitelist and /arena leave pass. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!inArena(player) || player.hasPermission(BYPASS_PERMISSION)) return;

        String command = event.getMessage().substring(1).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (isLeaveCommand(command)) return;
        for (String allowed : plugin.settings().duel().commandWhitelist()) {
            if (command.equals(allowed) || command.startsWith(allowed + " ")) return;
        }
        event.setCancelled(true);
        plugin.messages().send(player, "arenamode.command-blocked");
    }

    /** {@code /arena leave} under any alias of /arena. */
    private boolean isLeaveCommand(String command) {
        String[] parts = command.split(" ");
        if (parts.length != 2 || !parts[1].equals("leave")) return false;
        PluginCommand resolved = plugin.getCommand(parts[0]);
        return resolved != null && resolved.getName().equals("arena");
    }

    // ---------------------------------------------------------------- movement

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        Player player = event.getPlayer();
        if (!inArena(player) || plugin.arenaMode().isDying(player.getUniqueId())) return;
        double radius = plugin.settings().arenaMode().radius();
        Location spawn = plugin.arenaMode().spawn();
        if (radius <= 0.0 || spawn == null) return;

        if (isInside(spawn, event.getTo(), radius)) return;
        Messages messages = plugin.messages();
        if (isInside(spawn, event.getFrom(), radius)) {
            event.setCancelled(true);
            messages.actionBar(player, "arena.outside-bounds");
        } else {
            // outside already (knocked out, moved by a plugin): bring the player back
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && plugin.arenaMode().isIn(player.getUniqueId())) player.teleport(spawn);
            });
        }
    }

    /** Commands and portals cannot take a participant out of the arena; pearls and chorus fruit must land inside. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!inArena(player)) return;
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (BLOCKED_TELEPORTS.contains(cause)) {
            event.setCancelled(true);
            return;
        }
        if (cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && cause != PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) return;
        double radius = plugin.settings().arenaMode().radius();
        Location spawn = plugin.arenaMode().spawn();
        if (radius > 0.0 && spawn != null && !isInside(spawn, event.getTo(), radius)) {
            event.setCancelled(true);
            plugin.messages().actionBar(player, "arena.outside-bounds");
        }
    }

    private static boolean isInside(Location center, Location location, double radius) {
        if (location.getWorld() == null || !location.getWorld().equals(center.getWorld())) return false;
        return location.distanceSquared(center) <= radius * radius;
    }
}
