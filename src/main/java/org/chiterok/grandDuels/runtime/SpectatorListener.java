package org.chiterok.grandDuels.runtime;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.match.Match;
import org.chiterok.grandDuels.match.SpectatorManager;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Rules of spectating ({@code /spec}): spectators are invulnerable, confined to the duel's area, cannot teleport
 * away, change their gamemode or (by default) use commands, and always get their own state back when they quit or die.
 * The session itself lives in {@link SpectatorManager}.
 */
public final class SpectatorListener implements Listener {

    private static final String BYPASS_PERMISSION = "grandduels.bypass.commands";

    private final ClaudeDuels plugin;

    public SpectatorListener(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    private SpectatorManager spectators() {
        return plugin.spectators();
    }

    private boolean spectating(@Nullable Entity entity) {
        return entity instanceof Player player && spectators().isSpectating(player.getUniqueId());
    }

    // ---------------------------------------------------------------- leaving the server

    /** Quit, kick and ban all end up here: the player gets their own state back before it is saved. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        spectators().handleQuit(event.getPlayer());
    }

    // ---------------------------------------------------------------- damage and death

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (spectating(event.getEntity())) event.setCancelled(true);
    }

    /** A player that is still being moved to the duel (not yet in spectator mode) must not hit anybody. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) damager = shooter;
        if (spectating(damager)) event.setCancelled(true);
    }

    /** Only /kill and the like can kill a spectator: nothing is dropped, then the player is restored after respawning. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!spectators().isSpectating(player.getUniqueId())) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        spectators().handleDeath(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Location destination = spectators().respawnLocation(event.getPlayer());
        if (destination != null) event.setRespawnLocation(destination);
    }

    // ---------------------------------------------------------------- movement

    /** Spectators fly through walls, so the duel's area (plus spectate.padding) is their only boundary. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!spectators().isConfined(id)) return;
        Match match = spectators().matchOf(id);
        if (match == null || spectators().permits(match, event.getTo())) return;

        if (spectators().permits(match, event.getFrom())) {
            event.setCancelled(true);
            plugin.messages().actionBar(player, "spectate.outside-bounds");
            return;
        }
        // outside already (moved by a plugin, left the area while teleporting): bring the player back
        Location back = spectators().returnPoint(player);
        if (back == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && spectators().isConfined(id)) player.teleportAsync(back);
        });
    }

    /**
     * Blocks everything that would take a spectator out of the duel's area: the spectator teleport menu (it can
     * target any player of the world), commands of other plugins (/spawn, /home, /tpa, ...), portals. The teleports of
     * this plugin stay inside the area, and a restore only happens after the session has been removed.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!spectators().isSpectating(player.getUniqueId())) return;
        if (spectators().permits(player, event.getTo())) return;
        event.setCancelled(true);
        plugin.messages().actionBar(player, "spectate.teleport-blocked");
    }

    /** A spectator that is not a spectator any more would walk around the arena as a normal player. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (event.getNewGameMode() == GameMode.SPECTATOR || !spectators().isConfined(player.getUniqueId())) return;
        event.setCancelled(true);
        plugin.messages().send(player, "spectate.gamemode-blocked");
    }

    // ---------------------------------------------------------------- commands

    /** Other plugins' commands could change the inventory or move the player: only /spec and the duel whitelist pass. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!spectators().isSpectating(player.getUniqueId()) || !plugin.settings().spectate().blockCommands()
                || player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }
        String command = event.getMessage().substring(1).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (isSpectateCommand(command)) return;
        for (String allowed : plugin.settings().duel().commandWhitelist()) {
            if (command.equals(allowed) || command.startsWith(allowed + " ")) return;
        }
        event.setCancelled(true);
        plugin.messages().send(player, "spectate.command-blocked");
    }

    /** {@code /spec ...} under any alias of /spec. */
    private boolean isSpectateCommand(String command) {
        String label = command.split(" ")[0];
        PluginCommand resolved = plugin.getCommand(label);
        return resolved != null && resolved.getName().equals("spec");
    }
}
