package org.chiterok.grandDuels.match;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.config.Settings;
import org.chiterok.grandDuels.cooldown.RuleDefaults;
import org.chiterok.grandDuels.cooldown.RuleType;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.utils.StoredLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Arena mode ({@code /arena}): a free-for-all area. A player picks a standard kit and is teleported to the preset
 * arena location with that kit. When they die they are returned to the lobby (or where they came from when no lobby is
 * set) with their own inventory back; {@code /arena leave} does the same. The pre-arena state of every participant is
 * snapshotted and persisted, so a crash can never cost a player their inventory. Main thread only.
 */
public final class ArenaModeManager {

    public enum JoinResult { JOINED, DISABLED, NOT_CONFIGURED, WORLD_UNAVAILABLE, BUSY, NOT_STANDARD_KIT, FAILED }

    public enum LeaveResult { LEFT, NOT_IN_ARENA, IN_COMBAT }

    private static final class Participant {
        private final PlayerSnapshot snapshot;
        private final MatchSettings rules;
        private long combatUntilMillis;
        private boolean dying;
        private @Nullable BossBar bar;

        private Participant(PlayerSnapshot snapshot, MatchSettings rules) {
            this.snapshot = snapshot;
            this.rules = rules;
        }
    }

    /** Ticks to wait after a respawn before moving the player again (the client must finish loading). */
    private static final long RESPAWN_SETTLE_TICKS = 10L;

    private final ClaudeDuels plugin;
    private static final long BAR_REFRESH_TICKS = 4L;

    private final Map<UUID, Participant> participants = new HashMap<>();
    private BukkitTask barTask;

    public ArenaModeManager(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    /** Starts the boss bar refresh. Call once on enable. */
    public void start() {
        barTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshBars, BAR_REFRESH_TICKS, BAR_REFRESH_TICKS);
    }

    // ---------------------------------------------------------------- combat boss bar

    /**
     * Shows every participant the combat boss bar ({@code arena-mode.combat.bossbar}, texts in messages.yml): it
     * drains while the combat tag runs down and is hidden (or shows the "safe" text) when the tag has expired.
     */
    private void refreshBars() {
        Settings.CombatBarSettings config = plugin.settings().arenaMode().combat().bar();
        long tagMillis = plugin.settings().arenaMode().combatTagSeconds() * 1000L;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Participant> entry : participants.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Participant participant = entry.getValue();
            if (player == null) continue;
            long remaining = participant.combatUntilMillis - now;
            boolean tagged = tagMillis > 0L && remaining > 0L && !participant.dying;
            if (!config.enabled() || (!tagged && !config.showWhenSafe()) || participant.dying) {
                hideBar(player, participant);
                continue;
            }
            Messages messages = plugin.messages();
            net.kyori.adventure.text.Component title = tagged
                    ? messages.get("arenamode.bossbar-combat", Messages.ph("time", (remaining + 999L) / 1000L), player)
                    : messages.get("arenamode.bossbar-safe", java.util.Map.of(), player);
            float progress = tagged ? (float) Math.min(1.0, Math.max(0.0, remaining / (double) tagMillis)) : 1.0f;
            BossBar bar = participant.bar;
            if (bar == null) {
                bar = BossBar.bossBar(title, progress, config.color(), config.overlay());
                participant.bar = bar;
                player.showBossBar(bar);
            } else {
                bar.name(title).progress(progress).color(config.color()).overlay(config.overlay());
            }
        }
    }

    private static void hideBar(Player player, Participant participant) {
        BossBar bar = participant.bar;
        if (bar == null) return;
        player.hideBossBar(bar);
        participant.bar = null;
    }

    // ---------------------------------------------------------------- queries

    public boolean isIn(UUID playerId) {
        return participants.containsKey(playerId);
    }

    public int count() {
        return participants.size();
    }

    /** The duel rules (cooldowns, bans) that apply to the participant, or {@code null} outside arena mode. */
    public @Nullable MatchSettings rulesOf(UUID playerId) {
        Participant participant = participants.get(playerId);
        return participant == null ? null : participant.rules;
    }

    /** True while the player's death is being processed: they are protected until they are back in the lobby. */
    public boolean isDying(UUID playerId) {
        Participant participant = participants.get(playerId);
        return participant != null && participant.dying;
    }

    /** The arena spawn (also the centre of the movement radius), or {@code null} when not configured / not loaded. */
    public @Nullable Location spawn() {
        StoredLocation stored = plugin.settings().arenaMode().spawn();
        return stored == null ? null : stored.toLocation();
    }

    // ---------------------------------------------------------------- join / leave

    public JoinResult join(Player player, Kit kit) {
        Settings.ArenaModeSettings config = plugin.settings().arenaMode();
        if (!config.enabled()) return JoinResult.DISABLED;
        if (config.spawn() == null) return JoinResult.NOT_CONFIGURED;
        Location spawn = config.spawn().toLocation();
        if (spawn == null) return JoinResult.WORLD_UNAVAILABLE;
        if (kit.owner() != null) return JoinResult.NOT_STANDARD_KIT;

        UUID id = player.getUniqueId();
        if (plugin.isOccupied(id) || player.isDead()) return JoinResult.BUSY;

        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        if (!plugin.snapshots().save(id, snapshot)) return JoinResult.FAILED;
        MatchSettings rules = combatRules();
        participants.put(id, new Participant(snapshot, rules));

        plugin.queues().leave(id);
        plugin.requests().purge(id);

        player.closeInventory();
        player.leaveVehicle();
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setLevel(0);
        player.setExp(0.0f);
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
        plugin.cooldowns().clear(player);
        kit.apply(player, rules);
        player.getInventory().setHeldItemSlot(0);
        player.teleport(spawn);
        return JoinResult.JOINED;
    }

    /**
     * Rules a joining player is bound to: the server defaults, adjusted by {@code arena-mode.combat} when an admin
     * enabled it (cooldowns missing there keep their default; a missing ban list keeps the default bans).
     */
    private MatchSettings combatRules() {
        RuleDefaults defaults = plugin.menus().defaults();
        Settings.ArenaCombatSettings combat = plugin.settings().arenaMode().combat();
        if (!combat.enabled()) return MatchSettings.defaults(defaults);

        Map<RuleType, Double> cooldowns = new EnumMap<>(RuleType.class);
        for (RuleType type : RuleType.values()) {
            cooldowns.put(type, combat.cooldowns().getOrDefault(type, defaults.seconds(type)));
        }
        Set<RuleType> banned = combat.banned() != null ? combat.banned() : defaults.banned();
        return new MatchSettings(combat.customCooldowns(), cooldowns, banned);
    }

    /** Voluntary exit ({@code /arena leave}); refused while the player is combat tagged. */
    public LeaveResult leave(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null || participant.dying) return LeaveResult.NOT_IN_ARENA;
        if (System.currentTimeMillis() < participant.combatUntilMillis) return LeaveResult.IN_COMBAT;
        restore(player, participant, true);
        return LeaveResult.LEFT;
    }

    /** Seconds until the combat tag of the player expires (0 when not tagged). */
    public long combatSecondsLeft(UUID playerId) {
        Participant participant = participants.get(playerId);
        if (participant == null) return 0L;
        return Math.max(0L, (participant.combatUntilMillis - System.currentTimeMillis() + 999L) / 1000L);
    }

    /** Both players just fought each other: neither may leave for a while. */
    public void tagCombat(UUID first, UUID second) {
        long until = System.currentTimeMillis() + plugin.settings().arenaMode().combatTagSeconds() * 1000L;
        for (UUID id : new UUID[]{first, second}) {
            Participant participant = participants.get(id);
            if (participant != null) participant.combatUntilMillis = until;
        }
    }

    // ---------------------------------------------------------------- death, quit, shutdown

    /** Death listener: the player is respawned, then returned to the lobby with their own inventory. */
    public void handleDeath(Player victim) {
        Participant participant = participants.get(victim.getUniqueId());
        if (participant == null || participant.dying) return;
        participant.dying = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!victim.isOnline() || participants.get(victim.getUniqueId()) != participant) return;
            if (victim.isDead()) victim.spigot().respawn();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (victim.isOnline() && !victim.isDead() && participants.get(victim.getUniqueId()) == participant) {
                    restore(victim, participant, true);
                }
            }, RESPAWN_SETTLE_TICKS);
        });
    }

    /** Where a dying participant respawns: the lobby when set, otherwise where they were before the arena. */
    public @Nullable Location respawnLocation(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant == null) return null;
        // Respawn next to where the player died (chunks are loaded there); the move to the lobby / previous
        // location happens afterwards with an async teleport. Respawning straight into a far-away, unloaded
        // location left clients stuck on the loading screen.
        Location arena = spawn();
        return arena != null ? arena : destinationFor(participant);
    }

    /** Quit handler: the player is leaving, so restore synchronously. */
    public void handleQuit(Player player) {
        Participant participant = participants.get(player.getUniqueId());
        if (participant != null) restore(player, participant, false);
    }

    /** Plugin disable: return everyone synchronously. */
    public void shutdown() {
        if (barTask != null) barTask.cancel();
        for (UUID id : new ArrayList<>(participants.keySet())) {
            Player player = Bukkit.getPlayer(id);
            Participant participant = participants.get(id);
            if (player != null && participant != null) restore(player, participant, false);
        }
        participants.clear();
    }

    private void restore(Player player, Participant participant, boolean async) {
        participants.remove(player.getUniqueId());
        hideBar(player, participant);
        plugin.cooldowns().clear(player);
        participant.snapshot.restore(player, destinationFor(participant), async);
        plugin.snapshots().delete(player.getUniqueId());
    }

    private @Nullable Location destinationFor(Participant participant) {
        StoredLocation lobby = plugin.settings().duel().lobby();
        if (lobby != null) {
            Location resolved = lobby.toLocation();
            if (resolved != null) return resolved;
        }
        return participant.snapshot.location();
    }
}
