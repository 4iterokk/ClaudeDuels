package org.chiterok.grandDuels;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.chiterok.grandDuels.arena.ArenaManager;
import org.chiterok.grandDuels.command.admin.DuelsCommand;
import org.chiterok.grandDuels.command.player.ArenaModeCommand;
import org.chiterok.grandDuels.command.player.DuelCommand;
import org.chiterok.grandDuels.command.player.QueueCommand;
import org.chiterok.grandDuels.config.ConfigManager;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.config.Settings;
import org.chiterok.grandDuels.cooldown.PvPCooldownManager;
import org.chiterok.grandDuels.data.KitRatingManager;
import org.chiterok.grandDuels.data.PreferenceManager;
import org.chiterok.grandDuels.data.SnapshotRepository;
import org.chiterok.grandDuels.data.StatsManager;
import org.chiterok.grandDuels.gui.MenuManager;
import org.chiterok.grandDuels.kit.KitEditManager;
import org.chiterok.grandDuels.kit.KitManager;
import org.chiterok.grandDuels.kit.PlayerKitManager;
import org.chiterok.grandDuels.match.ArenaModeManager;
import org.chiterok.grandDuels.match.DuelMode;
import org.chiterok.grandDuels.match.DuelService;
import org.chiterok.grandDuels.match.MatchManager;
import org.chiterok.grandDuels.match.QueueManager;
import org.chiterok.grandDuels.match.RequestManager;
import org.chiterok.grandDuels.runtime.ArenaModeListener;
import org.chiterok.grandDuels.runtime.ArenaProtectionListener;
import org.chiterok.grandDuels.runtime.CombatRestrictionListener;
import org.chiterok.grandDuels.runtime.GuiListener;
import org.chiterok.grandDuels.runtime.KitEditorListener;
import org.chiterok.grandDuels.runtime.MatchListener;
import org.chiterok.grandDuels.runtime.PvPRulesListener;
import org.chiterok.grandDuels.utils.PapiParser;
import org.chiterok.grandDuels.utils.PlaceholderUtil;

import java.util.UUID;

/** Composition root: builds every service once and exposes them to the rest of the plugin. */
public final class GrandDuels extends JavaPlugin {

    private ConfigManager configs;
    private Messages messages;
    private ArenaManager arenas;
    private KitManager kits;
    private KitEditManager kitEdits;
    private PlayerKitManager playerKits;
    private QueueManager queues;
    private MenuManager menus;
    private PreferenceManager preferences;
    private PvPCooldownManager cooldowns;
    private SnapshotRepository snapshots;
    private StatsManager stats;
    private KitRatingManager kitRatings;
    private RequestManager requests;
    private MatchManager matches;
    private ArenaModeManager arenaMode;
    private DuelService duels;
    private PlaceholderUtil placeholders;

    @Override
    public void onEnable() {
        this.configs = new ConfigManager(this);
        this.messages = new Messages(configs);
        this.arenas = new ArenaManager(this);
        this.kits = new KitManager(this);
        this.kitEdits = new KitEditManager(this);
        this.playerKits = new PlayerKitManager(this);
        this.queues = new QueueManager(this);
        this.menus = new MenuManager(this);
        this.preferences = new PreferenceManager(this);
        this.cooldowns = new PvPCooldownManager(this);
        this.snapshots = new SnapshotRepository(this);
        this.stats = new StatsManager(this);
        this.kitRatings = new KitRatingManager(this);
        this.requests = new RequestManager(this);
        this.matches = new MatchManager(this);
        this.arenaMode = new ArenaModeManager(this);
        this.duels = new DuelService(this);

        arenas.load();
        kits.reload();
        preferences.load();
        stats.start();
        kitRatings.start();
        queues.start();
        arenaMode.start();

        registerListeners(new GuiListener(), new PvPRulesListener(this), new CombatRestrictionListener(this),
                new ArenaProtectionListener(this), new MatchListener(this), new KitEditorListener(this),
                new ArenaModeListener(this));
        registerCommand("duel", new DuelCommand(this));
        registerCommand("duels", new DuelsCommand(this));
        registerCommand("arena", new ArenaModeCommand(this));
        registerExecutor("ranked", new QueueCommand(this, DuelMode.RANKED));
        registerExecutor("unranked", new QueueCommand(this, DuelMode.UNRANKED));

        hookPlaceholderApi();

        // /reload or late enable: players are already online
        for (Player online : Bukkit.getOnlinePlayers()) {
            stats.preload(online);
            kitRatings.preload(online);
            if (matches.recover(online)) messages.send(online, "duels.recovered");
        }
        getLogger().info("GrandDuels enabled.");
    }

    @Override
    public void onDisable() {
        PapiParser.disable();
        if (queues != null) queues.stop();
        if (placeholders != null) placeholders.stop();
        if (kitEdits != null) kitEdits.shutdown();
        if (arenaMode != null) arenaMode.shutdown();
        if (matches != null) matches.shutdown();
        if (preferences != null) preferences.saveNow();
        if (requests != null) requests.clear();
        if (stats != null) stats.stop();
        if (kitRatings != null) kitRatings.stop();
    }

    /** Registers the %grandduels_...% placeholders when PlaceholderAPI is installed (it is a soft dependency). */
    private void hookPlaceholderApi() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        PapiParser.enable();
        if (PapiParser.isAvailable()) getLogger().info("PlaceholderAPI found: placeholders are parsed in menus.");
        try {
            this.placeholders = new PlaceholderUtil(this);
            if (placeholders.start()) getLogger().info("PlaceholderAPI found: %grandduels_...% placeholders registered.");
        } catch (LinkageError e) {
            this.placeholders = null;
            getLogger().warning("PlaceholderAPI is present but incompatible, placeholders are disabled: " + e);
        }
    }

    /**
     * True while the player is bound to one of the plugin's modes (duel, kit editing, arena mode) and so cannot start
     * another one.
     */
    public boolean isOccupied(UUID playerId) {
        return matches.isInMatch(playerId) || kitEdits.isEditing(playerId) || arenaMode.isIn(playerId);
    }

    /** Reloads config.yml, the language files (messages and menus), and kits.yml. Storage type changes need a restart. */
    public void reloadAll() {
        configs.reload();
        kits.reload();
        menus.reload();
    }

    private void registerListeners(Listener... listeners) {
        for (Listener listener : listeners) getServer().getPluginManager().registerEvents(listener, this);
    }

    private void registerExecutor(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml");
            return;
        }
        command.setExecutor(executor);
    }

    private void registerCommand(String name, org.bukkit.command.TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    public ConfigManager configs() {
        return configs;
    }

    public Settings settings() {
        return configs.settings();
    }

    public Messages messages() {
        return messages;
    }

    public ArenaManager arenas() {
        return arenas;
    }

    public KitManager kits() {
        return kits;
    }

    public PlayerKitManager playerKits() {
        return playerKits;
    }

    public QueueManager queues() {
        return queues;
    }

    public KitEditManager kitEdits() {
        return kitEdits;
    }

    public MenuManager menus() {
        return menus;
    }

    public PreferenceManager preferences() {
        return preferences;
    }

    public PvPCooldownManager cooldowns() {
        return cooldowns;
    }

    public SnapshotRepository snapshots() {
        return snapshots;
    }

    public StatsManager stats() {
        return stats;
    }

    public KitRatingManager kitRatings() {
        return kitRatings;
    }

    public RequestManager requests() {
        return requests;
    }

    public MatchManager matches() {
        return matches;
    }

    public ArenaModeManager arenaMode() {
        return arenaMode;
    }

    public DuelService duels() {
        return duels;
    }
}
