package org.chiterok.grandDuels.runtime;


import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.ItemStack;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.config.Settings;
import org.chiterok.grandDuels.kit.PlayerKitItemFilter;
import org.jetbrains.annotations.Nullable;


import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Enforces the restrictions of the player kit editor (a creative-mode session, see
 * {@link org.chiterok.grandDuels.kit.KitEditManager}). While a player is in such a session they cannot:
 * <ul>
 *   <li>change the world in any way (blocks, buckets, hanging entities, armor stands, containers, any interaction),</li>
 *   <li>hurt anybody or anything, or be hurt (the void teleports them back instead of killing them),</li>
 *   <li>drop or pick up items, throw or consume anything,</li>
 *   <li>leave the editing area (radius around where they started), change world, or teleport / use portals / mount,</li>
 *   <li>run commands other than {@code /duel kit save|cancel},</li>
 *   <li>pull items into the inventory that the kit rules forbid.</li>
 * </ul>
 * Nothing created in the session can leave it: the real inventory is restored from the snapshot when it ends.
 */
public final class KitEditorListener implements Listener {

    private static final String BYPASS_PERMISSION = "grandduels.bypass.commands";
    private static final Set<String> KIT_LABELS = Set.of("kit", "kits");
    private static final Set<String> ALLOWED_KIT_ACTIONS = Set.of("save", "cancel");
    private static final long WARN_INTERVAL_MS = 1000L;
    private static final long RESPAWN_SETTLE_TICKS = 10L;

    private final GrandDuels plugin;
    private final Map<UUID, Long> lastWarning = new HashMap<>();

    public KitEditorListener(GrandDuels plugin) {
        this.plugin = plugin;
    }

    private boolean editing(@Nullable Entity entity) {
        return entity instanceof Player player && plugin.kitEdits().isRestricted(player.getUniqueId());
    }

    private void warn(Player player, String path) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last != null && now - last < WARN_INTERVAL_MS) return;
        lastWarning.put(player.getUniqueId(), now);
        plugin.messages().actionBar(player, path);
    }

    private void deny(Player player, org.bukkit.event.Cancellable event) {
        event.setCancelled(true);
        warn(player, "playerkits.editor-blocked");
    }

    // ---------------------------------------------------------------- the world

    /**
     * Items can never be used. Blocks cannot be used either, except the crafting stations the server allows
     * ({@code player-kits.editor.allowed-inventories}: crafting table, anvil, enchanting table, ...), so that editors
     * can combine and enchant what they put into a kit.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!editing(player)) return;
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && isAllowedStation(event.getClickedBlock())) return;
        event.setUseInteractedBlock(Event.Result.DENY);
    }

    private boolean isAllowedStation(@Nullable Block block) {
        if (block == null) return false;
        InventoryType station = stationOf(block.getType());
        return station != null && plugin.settings().playerKits().editor().allowedInventories().contains(station);
    }

    /** The screen a crafting-station block opens, or {@code null} for every other block. */
    private static @Nullable InventoryType stationOf(Material material) {
        return switch (material) {
            case CRAFTING_TABLE -> InventoryType.WORKBENCH;
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL -> InventoryType.ANVIL;
            case ENCHANTING_TABLE -> InventoryType.ENCHANTING;
            case GRINDSTONE -> InventoryType.GRINDSTONE;
            case SMITHING_TABLE -> InventoryType.SMITHING;
            case STONECUTTER -> InventoryType.STONECUTTER;
            case LOOM -> InventoryType.LOOM;
            case CARTOGRAPHY_TABLE -> InventoryType.CARTOGRAPHY;
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (editing(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (editing(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (editing(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        if (editing(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (editing(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (editing(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (editing(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingPlace(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && editing(player)) deny(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (editing(event.getRemover()) && event.getRemover() instanceof Player player) deny(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOpenInventory(InventoryOpenEvent event) {
        // the editor's own inventory screen does not fire this event; any other screen is a container,
        // except the crafting stations the server allows
        if (!editing(event.getPlayer())) return;
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CRAFTING
                || plugin.settings().playerKits().editor().allowedInventories().contains(type)) return;
        event.setCancelled(true);
    }

    // ---------------------------------------------------------------- combat, damage, items

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (!editing(event.getEntity())) return;
        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            Player player = (Player) event.getEntity();
            Location origin = plugin.kitEdits().origin(player.getUniqueId());
            if (origin != null) Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && plugin.kitEdits().isRestricted(player.getUniqueId())) player.teleport(origin);
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) damager = shooter;
        if (editing(damager)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && editing(player)) deny(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (editing(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (editing(event.getPlayer())) deny(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (editing(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        if (editing(event.getPlayer())) event.setCancelled(true);
    }

    /**
     * Creative inventory actions: dropping an item by clicking outside the window is blocked, and an item the kit
     * rules forbid is refused at once (the same rules are enforced again when the kit is saved).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !editing(player)) return;
        if (event.getSlotType() == InventoryType.SlotType.OUTSIDE) {
            event.setCancelled(true);
            return;
        }
        ItemStack cursor = event.getCursor();
        if (cursor.getType().isAir()) return;
        Settings.PlayerKitSettings rules = plugin.settings().playerKits();
        if (new PlayerKitItemFilter(rules.bannedMaterials(), rules.editor().maxItemBytes()).test(cursor)) {
            event.setCancelled(true);
            event.setCursor(new ItemStack(org.bukkit.Material.AIR));
            warn(player, "playerkits.editor-item-rejected");
        }
    }

    // ---------------------------------------------------------------- movement

    /** Keeps the editor inside the editing area: no leaving it, no other world. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        if (!editing(player)) return;
        double radius = plugin.settings().playerKits().editor().radius();
        if (radius <= 0.0) return;
        Location origin = plugin.kitEdits().origin(player.getUniqueId());
        if (origin == null) return;

        if (isInside(origin, event.getTo(), radius)) return;
        warn(player, "playerkits.editor-out-of-area");
        if (isInside(origin, event.getFrom(), radius)) {
            event.setCancelled(true);
        } else {
            // already outside (pushed or moved by something else): bring the player back
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && plugin.kitEdits().isRestricted(player.getUniqueId())) player.teleport(origin);
            });
        }
    }

    private static boolean isInside(Location origin, Location location, double radius) {
        if (location.getWorld() == null || !location.getWorld().equals(origin.getWorld())) return false;
        return location.distanceSquared(origin) <= radius * radius;
    }

    /** Only plugin teleports (the one that restores the player) are allowed; pearls, commands, portals are not. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!editing(event.getPlayer())) return;
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (editing(event.getEntered())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (editing(event.getEntity())) event.setCancelled(true);
    }

    // ---------------------------------------------------------------- commands

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!editing(player) || player.hasPermission(BYPASS_PERMISSION)) return;
        if (isFinishCommand(event.getMessage())) return;
        event.setCancelled(true);
        plugin.messages().send(player, "playerkits.editor-command-blocked");
    }

    /** {@code /duel kit save|cancel ...} under any alias of /duel (resolved through the command map). */
    private boolean isFinishCommand(String message) {
        String[] parts = message.substring(1).trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length < 3) return false;
        PluginCommand command = plugin.getCommand(parts[0]);
        return command != null && command.getName().equals("duel")
                && KIT_LABELS.contains(parts[1]) && ALLOWED_KIT_ACTIONS.contains(parts[2]);
    }

    // ---------------------------------------------------------------- death

    /** Cannot normally happen (all damage is cancelled) but plugins or /kill could: never drop the creative items. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!editing(player)) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (player.isDead()) player.spigot().respawn();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && plugin.kitEdits().cancelPlayerSession(player)) {
                    plugin.messages().send(player, "playerkits.editor-cancelled");
                }
            }, RESPAWN_SETTLE_TICKS);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!editing(player)) return;
        Location origin = plugin.kitEdits().origin(player.getUniqueId());
        if (origin != null) event.setRespawnLocation(origin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastWarning.remove(event.getPlayer().getUniqueId());
    }
}
