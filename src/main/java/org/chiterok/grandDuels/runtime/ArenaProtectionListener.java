package org.chiterok.grandDuels.runtime;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.Arena;
import org.chiterok.grandDuels.match.Match;

/**
 * Keeps arenas intact. With {@code duel.building-enabled=false} every block change inside an active arena is
 * cancelled (fire, liquid flow and explosions included). With it enabled, duelists may change blocks and the original
 * state of each block is recorded for rollback; outsiders still cannot touch an active arena.
 */
public final class ArenaProtectionListener implements Listener {

    private final ClaudeDuels plugin;

    public ArenaProtectionListener(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    private boolean building() {
        return plugin.settings().duel().buildingEnabled();
    }

    private Arena activeArenaAt(Location location) {
        return plugin.arenas().findActiveAt(location);
    }

    /** @return true if the change by {@code player} at {@code block} is allowed (and must be recorded by the caller). */
    private boolean mayChange(Player player, Block block) {
        Arena arena = activeArenaAt(block.getLocation());
        if (arena == null) return true;
        if (!building()) return false;
        Match match = plugin.matches().of(player);
        return match != null && match.arena() == arena && match.isFighting();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Arena arena = activeArenaAt(block.getLocation());
        if (arena == null) return;
        if (!mayChange(event.getPlayer(), block)) {
            event.setCancelled(true);
            return;
        }
        arena.rollback().record(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        Arena arena = activeArenaAt(block.getLocation());
        if (arena == null) return;
        if (!mayChange(event.getPlayer(), block)) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof BlockMultiPlaceEvent multi) {
            multi.getReplacedBlockStates().forEach(arena.rollback()::record);
        } else {
            arena.rollback().record(event.getBlockReplacedState());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        handleBucket(event.getPlayer(), event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        handleBucket(event.getPlayer(), event.getBlock(), event);
    }

    private void handleBucket(Player player, Block block, Cancellable event) {
        Arena arena = activeArenaAt(block.getLocation());
        if (arena == null) return;
        if (!mayChange(player, block)) {
            event.setCancelled(true);
            return;
        }
        arena.rollback().record(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent event) {
        Block target = event.getToBlock();
        Arena arena = activeArenaAt(target.getLocation());
        if (arena == null) return;
        if (!building()) {
            event.setCancelled(true);
            return;
        }
        arena.rollback().record(target);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Block block = event.getBlock();
        Arena arena = activeArenaAt(block.getLocation());
        if (arena == null) return;
        if (!building()) {
            event.setCancelled(true);
            return;
        }
        arena.rollback().record(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Block block = event.getBlock();
        Arena arena = activeArenaAt(block.getLocation());
        if (arena == null) return;
        if (!building()) {
            event.setCancelled(true);
            return;
        }
        arena.rollback().record(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        Block block = event.getBlock();
        Arena arena = activeArenaAt(block.getLocation());
        if (arena == null) return;
        if (!building()) {
            event.setCancelled(true);
            return;
        }
        arena.rollback().record(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        Arena arena = activeArenaAt(block.getLocation());
        if (arena == null) return;
        if (!building()) {
            event.setCancelled(true);
            return;
        }
        arena.rollback().record(block);
    }

    /** Explosions still hurt players, but only change blocks when building is enabled (and then they roll back). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosion(event.getLocation(), event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.getBlock().getLocation(), event.blockList());
    }

    private void filterExplosion(Location origin, java.util.List<Block> blocks) {
        Arena originArena = activeArenaAt(origin);
        blocks.removeIf(block -> {
            Arena arena = activeArenaAt(block.getLocation());
            if (arena == null) return originArena != null && !building();
            if (!building()) return true;
            arena.rollback().record(block);
            return false;
        });
    }
}
