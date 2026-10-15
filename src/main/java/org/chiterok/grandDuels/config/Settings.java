package org.chiterok.grandDuels.config;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.inventory.InventoryType;
import org.chiterok.grandDuels.cooldown.RuleType;
import org.chiterok.grandDuels.utils.StoredLocation;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, typed snapshot of config.yml. A new instance is built on every reload.
 * Item restrictions and cooldowns are NOT here: they live in menu/cooldowns.yml (see RuleDefaults).
 */
public record Settings(String language, StorageType storageType, DuelSettings duel, RuleSettings rules,
                       MatchmakingSettings matchmaking, PlayerKitSettings playerKits, ArenaModeSettings arenaMode,
                       SpectateSettings spectate) {

    /** Language used when {@code language} in config.yml is missing or malformed. */
    public static final String DEFAULT_LANGUAGE = "en";

    public enum StorageType { YAML, SQLITE }

    /** GLOBAL: one ELO per player. KIT: ranked duels move a separate ELO for every standard kit. */
    public enum RatingMode { GLOBAL, KIT }

    /** Inventory screens a player-kit editor may open (the player's own inventory is always allowed). */
    public static final Set<InventoryType> DEFAULT_EDITOR_INVENTORIES = Set.copyOf(EnumSet.of(
            InventoryType.WORKBENCH, InventoryType.ANVIL, InventoryType.ENCHANTING, InventoryType.GRINDSTONE,
            InventoryType.SMITHING));

    public enum ReturnMode { PREVIOUS_LOCATION, LOBBY }

    public record DuelSettings(int requestExpireSeconds, boolean useKitSelector, String defaultKit,
                               int countdownSeconds, int celebrationSeconds, int maxDurationSeconds,
                               ReturnMode returnMode, @Nullable StoredLocation lobby, double boundaryPadding,
                               boolean buildingEnabled, int rollbackBatchSize, Set<String> commandWhitelist) {}

    public record WeaponRule(double maxDamage, double knockbackMultiplier) {}

    public record RuleSettings(boolean blockItemDrop, boolean blockItemPickup, WeaponRule mace, WeaponRule spear) {}

    /**
     * @param kFactor            ELO k-factor
     * @param initialEloRange    ranked: allowed ELO difference when a player joins the queue
     * @param eloRangePerSecond  ranked: how much the allowed difference grows per second of waiting
     * @param maxEloRange        ranked: upper limit of the allowed difference (0 = unlimited)
     * @param ratingMode         what ranked duels rate: one global ELO or one ELO per standard kit
     */
    public record MatchmakingSettings(int kFactor, int initialEloRange, double eloRangePerSecond, int maxEloRange,
                                      RatingMode ratingMode) {}

    /**
     * @param enabled        false: {@code /duel kit create} copies the inventory instead of opening the editor
     * @param timeoutSeconds editing sessions are cancelled after this long (0 = never)
     * @param radius         editors cannot move farther than this from where they started (0 = unlimited)
     * @param maxItemBytes   an item whose serialized form is larger than this is rejected (0 = unlimited)
     * @param zone           where player kit editors are teleported to (and which the radius is measured from);
     *                       {@code null} = they edit where they stand
     * @param allowedInventories crafting-style screens (crafting table, anvil, ...) editors may use
     */
    public record KitEditorSettings(boolean enabled, int timeoutSeconds, double radius, int maxItemBytes,
                                    @Nullable StoredLocation zone, Set<InventoryType> allowedInventories) {

        public KitEditorSettings {
            allowedInventories = Set.copyOf(allowedInventories);
        }
    }

    public record PlayerKitSettings(int maxPerPlayer, Set<Material> bannedMaterials, KitEditorSettings editor) {}

    /**
     * @param enabled          false disables {@code /arena}
     * @param spawn            where players are teleported after choosing a kit (null until an admin sets it)
     * @param radius           players cannot move farther than this from the spawn (0 = unlimited)
     * @param combatTagSeconds {@code /arena leave} is refused this long after taking or dealing player damage
     * @param combat           item cooldowns / bans of arena mode (see {@link ArenaCombatSettings})
     */
    /**
     * Boss bar shown to arena players ({@code arena-mode.combat.bossbar}); the texts are in messages.yml.
     *
     * @param showWhenSafe also show the bar (full, with the "safe" text) while the player is not combat tagged
     */
    public record CombatBarSettings(boolean enabled, BossBar.Color color, BossBar.Overlay overlay,
                                    boolean showWhenSafe) {

        public static final CombatBarSettings DEFAULT = new CombatBarSettings(true, BossBar.Color.RED,
                BossBar.Overlay.PROGRESS, false);
    }

    public record ArenaModeSettings(boolean enabled, @Nullable StoredLocation spawn, double radius,
                                    int combatTagSeconds, ArenaCombatSettings combat) {}

    /**
     * Spectating running duels ({@code /spec}).
     *
     * @param enabled       false disables {@code /spec} (running sessions finish with their duel)
     * @param padding       how far outside the duel's arena boundary spectators may fly (0 = only inside it)
     * @param maxPerMatch   spectator limit per duel (0 = unlimited)
     * @param blockCommands true: spectators may only use /spec and the duel command whitelist
     */
    public record SpectateSettings(boolean enabled, double padding, int maxPerMatch, boolean blockCommands) {}

    /**
     * Combat rules of arena mode, configured in {@code arena-mode.combat}. When not {@code enabled}, arena mode uses
     * the server defaults of menu/cooldowns.yml like duels do.
     *
     * @param customCooldowns whether the cooldowns are enforced at all
     * @param cooldowns       seconds per item; items missing here keep their server default
     * @param banned          banned items/abilities; {@code null} = keep the server default list
     */
    public record ArenaCombatSettings(boolean enabled, boolean customCooldowns, Map<RuleType, Double> cooldowns,
                                      @Nullable Set<RuleType> banned, CombatBarSettings bar) {

        public static final ArenaCombatSettings DISABLED = new ArenaCombatSettings(false, true, Map.of(), null,
                CombatBarSettings.DEFAULT);

        public ArenaCombatSettings {
            cooldowns = Map.copyOf(cooldowns);
            banned = banned == null ? null : Set.copyOf(banned);
        }
    }

    public static Settings from(FileConfiguration c) {
        Set<String> whitelist = new LinkedHashSet<>();
        for (String entry : c.getStringList("duel.command-whitelist")) {
            String normalized = entry.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("/")) normalized = normalized.substring(1);
            if (!normalized.isEmpty()) whitelist.add(normalized);
        }

        DuelSettings duel = new DuelSettings(
                Math.max(5, c.getInt("duel.request-expire-seconds", 30)),
                c.getBoolean("duel.use-kit-selector", true),
                c.getString("duel.default-kit", "nodebuff").toLowerCase(Locale.ROOT),
                Math.max(0, c.getInt("duel.countdown-seconds", 5)),
                Math.max(0, c.getInt("duel.celebration-seconds", 5)),
                Math.max(0, c.getInt("duel.max-duration-seconds", 600)),
                parseEnum(ReturnMode.class, c.getString("duel.return-mode"), ReturnMode.PREVIOUS_LOCATION),
                StoredLocation.read(c.getConfigurationSection("lobby")),
                Math.max(0.0, c.getDouble("duel.boundary-padding", 40.0)),
                c.getBoolean("duel.building-enabled", false),
                Math.max(1, c.getInt("duel.rollback-batch-size", 500)),
                Set.copyOf(whitelist));

        RuleSettings rules = new RuleSettings(
                c.getBoolean("rules.block-item-drop", true),
                c.getBoolean("rules.block-item-pickup", true),
                weaponRule(c.getConfigurationSection("rules.mace")),
                weaponRule(c.getConfigurationSection("rules.spear")));

        Set<Material> banned = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("player-kits.banned-materials")) {
            Material material = Material.matchMaterial(name);
            if (material != null) banned.add(material);
        }

        String language = c.getString("language", DEFAULT_LANGUAGE).trim().toLowerCase(Locale.ROOT);
        if (!language.matches("[a-z0-9_-]{2,16}")) language = DEFAULT_LANGUAGE;

        KitEditorSettings editor = new KitEditorSettings(
                c.getBoolean("player-kits.editor.enabled", true),
                Math.max(0, c.getInt("player-kits.editor.timeout-seconds", 600)),
                Math.max(0.0, c.getDouble("player-kits.editor.radius", 10.0)),
                Math.max(0, c.getInt("player-kits.editor.max-item-bytes", 4096)),
                StoredLocation.read(c.getConfigurationSection("player-kits.editor.zone")),
                editorInventories(c));
        ArenaModeSettings arenaMode = new ArenaModeSettings(
                c.getBoolean("arena-mode.enabled", true),
                StoredLocation.read(c.getConfigurationSection("arena-mode.spawn")),
                Math.max(0.0, c.getDouble("arena-mode.radius", 60.0)),
                Math.max(0, c.getInt("arena-mode.combat-tag-seconds", 10)),
                arenaCombat(c.getConfigurationSection("arena-mode.combat")));

        return new Settings(
                language,
                parseEnum(StorageType.class, c.getString("storage.type"), StorageType.YAML),
                duel, rules,
                new MatchmakingSettings(Math.max(1, c.getInt("matchmaking.k-factor", 32)),
                        Math.max(0, c.getInt("matchmaking.ranked.initial-elo-range", 100)),
                        Math.max(0.0, c.getDouble("matchmaking.ranked.elo-range-per-second", 5.0)),
                        Math.max(0, c.getInt("matchmaking.ranked.max-elo-range", 800)),
                        parseEnum(RatingMode.class, c.getString("matchmaking.rating-mode"), RatingMode.GLOBAL)),
                new PlayerKitSettings(Math.max(0, c.getInt("player-kits.max-per-player", 5)), Set.copyOf(banned), editor),
                arenaMode,
                new SpectateSettings(c.getBoolean("spectate.enabled", true),
                        Math.max(0.0, c.getDouble("spectate.padding", 20.0)),
                        Math.max(0, c.getInt("spectate.max-per-match", 0)),
                        c.getBoolean("spectate.block-commands", true)));
    }

    private static Set<InventoryType> editorInventories(FileConfiguration c) {
        if (!c.isList("player-kits.editor.allowed-inventories")) return DEFAULT_EDITOR_INVENTORIES;
        Set<InventoryType> allowed = EnumSet.noneOf(InventoryType.class);
        for (String name : c.getStringList("player-kits.editor.allowed-inventories")) {
            InventoryType type = parseEnum(InventoryType.class, name, null);
            if (type != null) allowed.add(type);
        }
        return allowed;
    }

    private static ArenaCombatSettings arenaCombat(@Nullable ConfigurationSection s) {
        if (s == null) return ArenaCombatSettings.DISABLED;
        Map<RuleType, Double> cooldowns = new EnumMap<>(RuleType.class);
        ConfigurationSection configured = s.getConfigurationSection("cooldowns");
        if (configured != null) {
            for (String key : configured.getKeys(false)) {
                RuleType type = RuleType.fromKey(key);
                if (type != null && type.hasCooldown()) cooldowns.put(type, Math.max(0.0, configured.getDouble(key)));
            }
        }
        Set<RuleType> banned = null;
        if (s.isList("banned")) {
            banned = EnumSet.noneOf(RuleType.class);
            List<String> names = s.getStringList("banned");
            for (String name : names) {
                RuleType type = RuleType.fromKey(name);
                if (type != null) banned.add(type);
            }
        }
        CombatBarSettings bar = CombatBarSettings.DEFAULT;
        ConfigurationSection barSection = s.getConfigurationSection("bossbar");
        if (barSection != null) {
            bar = new CombatBarSettings(barSection.getBoolean("enabled", true),
                    parseEnum(BossBar.Color.class, barSection.getString("color"), BossBar.Color.RED),
                    parseEnum(BossBar.Overlay.class, barSection.getString("style"), BossBar.Overlay.PROGRESS),
                    barSection.getBoolean("show-when-safe", false));
        }
        return new ArenaCombatSettings(s.getBoolean("enabled", false), s.getBoolean("custom-cooldowns", true),
                cooldowns, banned, bar);
    }

    private static WeaponRule weaponRule(@Nullable ConfigurationSection s) {
        if (s == null) return new WeaponRule(0.0, 1.0);
        return new WeaponRule(Math.max(0.0, s.getDouble("max-damage", 0.0)),
                Math.max(0.0, s.getDouble("knockback-multiplier", 1.0)));
    }

    private static <E extends Enum<E>> @Nullable E parseEnum(Class<E> type, @Nullable String raw, @Nullable E fallback) {
        if (raw == null) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
