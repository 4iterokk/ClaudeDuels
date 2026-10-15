package org.chiterok.grandDuels.runtime;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.cooldown.RuleType;
import org.chiterok.grandDuels.match.Match;

import java.util.Locale;
import java.util.Set;

/** Anti-exploit rules inside duels: elytra, commands, item drop/pickup, freezing and arena boundaries. */
public final class CombatRestrictionListener implements Listener {

    private static final String BYPASS_PERMISSION = "grandduels.bypass.commands";

    private final ClaudeDuels plugin;

    public CombatRestrictionListener(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding() || !(event.getEntity() instanceof Player player)) return;
        Match match = plugin.matches().of(player);
        if (match == null || !match.settings().isBanned(RuleType.ELYTRA)) return;
        event.setCancelled(true);
        plugin.messages().actionBar(player, "duels.elytra-blocked");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (plugin.matches().of(player) == null || player.hasPermission(BYPASS_PERMISSION)) return;

        String command = event.getMessage().substring(1).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        Set<String> whitelist = plugin.settings().duel().commandWhitelist();
        for (String allowed : whitelist) {
            if (command.equals(allowed) || command.startsWith(allowed + " ")) return;
        }
        event.setCancelled(true);
        plugin.messages().send(player, "duels.command-blocked");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.settings().rules().blockItemDrop()) return;
        Player player = event.getPlayer();
        if (plugin.matches().of(player) == null) return;
        event.setCancelled(true);
        plugin.messages().actionBar(player, "duels.item-blocked");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!plugin.settings().rules().blockItemPickup() || !(event.getEntity() instanceof Player player)) return;
        if (plugin.matches().of(player) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        Match match = plugin.matches().of(player);
        if (match == null) return;

        if (match.isFrozen()) {
            Location frozen = event.getFrom().clone();
            frozen.setYaw(event.getTo().getYaw());
            frozen.setPitch(event.getTo().getPitch());
            event.setTo(frozen);
            return;
        }
        if (match.isFighting() && event.hasChangedBlock() && !isInside(match, event.getTo())) {
            event.setCancelled(true);
            plugin.messages().actionBar(player, "arena.outside-bounds");
        }
    }

    // ---------------------------------------------------------------- countdown: nothing may be used yet

    private boolean inCountdown(Player player) {
        Match match = plugin.matches().of(player);
        return match != null && match.isFrozen();
    }

    /** Items and blocks cannot be used during the countdown (food, pearls, bows, potions, placing, ...). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCountdownInteract(PlayerInteractEvent event) {
        if (!inCountdown(event.getPlayer())) return;
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCountdownConsume(PlayerItemConsumeEvent event) {
        if (inCountdown(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCountdownShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player && inCountdown(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCountdownLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && inCountdown(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCountdownSwap(PlayerSwapHandItemsEvent event) {
        if (inCountdown(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCountdownInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && inCountdown(player)) event.setCancelled(true);
    }

    /** Blocks pearls/chorus fruit that would land outside the arena boundary. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && cause != PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) return;
        Player player = event.getPlayer();
        Match match = plugin.matches().of(player);
        if (match == null || isInside(match, event.getTo())) return;
        event.setCancelled(true);
        plugin.messages().actionBar(player, "arena.outside-bounds");
    }

    private static boolean isInside(Match match, Location location) {
        BoundingBox bounds = match.arena().activeBounds();
        if (bounds == null || location.getWorld() == null) return true;
        if (!location.getWorld().getName().equals(match.arena().worldName())) return false;
        return bounds.contains(location.getX(), location.getY(), location.getZ());
    }
}
