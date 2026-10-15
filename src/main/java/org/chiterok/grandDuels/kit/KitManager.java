package org.chiterok.grandDuels.kit;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.config.Settings;
import org.chiterok.grandDuels.match.DuelMode;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Loads kits from kits.yml and saves kits from a player's current loadout. */
public final class KitManager {

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final ClaudeDuels plugin;
    private final KitItemCodec codec;
    private volatile Map<String, Kit> kits = Map.of();

    public KitManager(ClaudeDuels plugin) {
        this.plugin = plugin;
        this.codec = new KitItemCodec(plugin);
    }

    public static boolean isValidId(String id) {
        return VALID_ID.matcher(id).matches();
    }

    public void reload() {
        Map<String, Kit> loaded = new LinkedHashMap<>();
        ConfigurationSection root = plugin.configs().kits().getConfigurationSection("kits");
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(rawId);
                if (section == null) continue;
                String id = rawId.toLowerCase(Locale.ROOT);
                try {
                    loaded.put(id, parse(id, section, null));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Skipping kit '" + rawId + "': " + e.getMessage());
                }
            }
        }
        this.kits = loaded;
        plugin.getLogger().info("Loaded " + loaded.size() + " kit(s).");
    }

    public @Nullable Kit get(String id) {
        return kits.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Kit> all() {
        return List.copyOf(kits.values());
    }

    /** Standard kits that may currently be chosen in the queue of {@code mode}. */
    public List<Kit> queueKits(DuelMode mode) {
        List<Kit> list = new ArrayList<>();
        for (Kit kit : kits.values()) {
            if (kit.isEnabledFor(mode)) list.add(kit);
        }
        return list;
    }

    public List<String> ids() {
        return new ArrayList<>(kits.keySet());
    }

    /** Parses a kit section (server kits and player kits share the format). */
    public Kit parse(String id, ConfigurationSection s, @Nullable UUID owner) {
        Set<String> used = new TreeSet<>();

        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        ConfigurationSection itemSection = s.getConfigurationSection("items");
        if (itemSection != null) {
            for (String key : itemSection.getKeys(false)) {
                int slot;
                try {
                    slot = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("item slot '" + key + "' is not a number");
                }
                if (slot < 0 || slot > 35) throw new IllegalArgumentException("item slot " + slot + " is outside 0-35");
                items.put(slot, decodeSection(itemSection, key, "items." + key, used));
            }
        }

        ConfigurationSection armor = s.getConfigurationSection("armor");
        ItemStack helmet = armor == null ? null : decodeOptional(armor, "helmet", used);
        ItemStack chestplate = armor == null ? null : decodeOptional(armor, "chestplate", used);
        ItemStack leggings = armor == null ? null : decodeOptional(armor, "leggings", used);
        ItemStack boots = armor == null ? null : decodeOptional(armor, "boots", used);
        ItemStack offhand = decodeOptional(s, "offhand", used);

        List<PotionEffect> effects = new ArrayList<>();
        for (Map<?, ?> effect : s.getMapList("effects")) effects.add(codec.parseEffect(effect));

        ItemStack icon = parseIcon(s, items, used);
        return new Kit(id, localizedName(s, "&f" + id), icon, List.copyOf(localizedLore(s)),
                Map.copyOf(items), helmet, chestplate, leggings, boots, offhand, List.copyOf(effects),
                Set.copyOf(used), s.getBoolean("ranked", true),
                s.getBoolean("unranked", true), owner);
    }

    // ---------------------------------------------------------------- localized texts

    /**
     * {@code display-name} is either a plain string (every language) or a section of strings keyed by language code.
     * The active language is used, then English, then the first one listed.
     */
    private String localizedName(ConfigurationSection kit, String fallback) {
        ConfigurationSection variants = kit.getConfigurationSection("display-name");
        if (variants == null) return kit.getString("display-name", fallback);
        String key = variantKey(variants);
        return key == null ? fallback : variants.getString(key, fallback);
    }

    /** {@code description} is either a list (every language) or a section of lists keyed by language code. */
    private List<String> localizedLore(ConfigurationSection kit) {
        ConfigurationSection variants = kit.getConfigurationSection("description");
        if (variants == null) return kit.getStringList("description");
        String key = variantKey(variants);
        return key == null ? List.of() : variants.getStringList(key);
    }

    private @Nullable String variantKey(ConfigurationSection variants) {
        for (String candidate : List.of(plugin.settings().language(), Settings.DEFAULT_LANGUAGE)) {
            if (variants.isSet(candidate)) return candidate;
        }
        for (String key : variants.getKeys(false)) return key;
        return null;
    }

    private ItemStack decodeSection(ConfigurationSection parent, String key, String path, Set<String> used) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) throw new IllegalArgumentException(path + " must be a section");
        try {
            return codec.decode(section, used);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    private @Nullable ItemStack decodeOptional(ConfigurationSection parent, String key, Set<String> used) {
        return parent.isConfigurationSection(key) ? decodeSection(parent, key, key, used) : null;
    }

    private ItemStack parseIcon(ConfigurationSection s, Map<Integer, ItemStack> items, Set<String> used) {
        if (s.isConfigurationSection("icon")) return decodeSection(s, "icon", "icon", used);
        String name = s.getString("icon");
        if (name != null) {
            Material material = Material.matchMaterial(name);
            if (material == null || material.isAir() || !material.isItem()) {
                throw new IllegalArgumentException("icon: unknown material '" + name + "'");
            }
            return new ItemStack(material);
        }
        if (!items.isEmpty()) return new ItemStack(items.values().iterator().next().getType());
        return new ItemStack(Material.IRON_SWORD);
    }

    /** Overwrites items/armor/offhand/effects of kit {@code id} (creating it if needed) from the player's loadout. */
    public void save(String id, Player player) {
        FileConfiguration yaml = plugin.configs().kits();
        String existingKey = findKey(id);
        ConfigurationSection kit = existingKey == null ? null : yaml.getConfigurationSection("kits." + existingKey);
        if (kit == null) {
            kit = yaml.createSection("kits." + id);
            kit.set("display-name", "&f" + id);
            ItemStack inHand = player.getInventory().getItemInMainHand();
            kit.set("icon", inHand.getType().isAir() ? Material.IRON_SWORD.name() : inHand.getType().name());
            kit.set("description", List.of("&7Saved from a player loadout."));
        }
        writeLoadout(kit, player, true, Set.of());

        plugin.configs().kitsFile().save();
        reload();
    }

    /**
     * Writes the player's hotbar/inventory, armor, offhand (and optionally active effects) into {@code kit},
     * replacing any previous loadout there. Items whose material is in {@code excluded} are skipped.
     * @return the number of skipped items
     */
    public int writeLoadout(ConfigurationSection kit, Player player, boolean includeEffects, Set<Material> excluded) {
        return writeLoadout(kit, player, includeEffects, item -> excluded.contains(item.getType()));
    }

    /** Same as above, but every item for which {@code rejected} returns true is skipped. */
    public int writeLoadout(ConfigurationSection kit, Player player, boolean includeEffects,
                            Predicate<ItemStack> rejected) {
        kit.set("items", null);
        kit.set("armor", null);
        kit.set("offhand", null);
        kit.set("effects", null);

        int skipped = 0;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isEmpty(item)) continue;
            if (rejected.test(item)) {
                skipped++;
                continue;
            }
            codec.encode(kit.createSection("items." + slot), item);
        }
        skipped += writeArmor(kit, "helmet", inventory.getHelmet(), rejected);
        skipped += writeArmor(kit, "chestplate", inventory.getChestplate(), rejected);
        skipped += writeArmor(kit, "leggings", inventory.getLeggings(), rejected);
        skipped += writeArmor(kit, "boots", inventory.getBoots(), rejected);
        ItemStack offhand = inventory.getItemInOffHand();
        if (!isEmpty(offhand)) {
            if (rejected.test(offhand)) skipped++;
            else codec.encode(kit.createSection("offhand"), offhand);
        }

        if (includeEffects) {
            List<Map<String, Object>> effects = new ArrayList<>();
            for (PotionEffect effect : player.getActivePotionEffects()) effects.add(codec.encodeEffect(effect));
            if (!effects.isEmpty()) kit.set("effects", effects);
        }
        return skipped;
    }

    /** Key of the kit section in kits.yml (kit ids are case-insensitive), or {@code null}. */
    private @Nullable String findKey(String id) {
        ConfigurationSection root = plugin.configs().kits().getConfigurationSection("kits");
        if (root == null) return null;
        for (String key : root.getKeys(false)) {
            if (key.equalsIgnoreCase(id)) return key;
        }
        return null;
    }

    /** Removes the kit from kits.yml. @return false if it does not exist. */
    public boolean delete(String id) {
        String key = findKey(id);
        if (key == null) return false;
        plugin.configs().kits().set("kits." + key, null);
        plugin.configs().kitsFile().save();
        reload();
        plugin.arenas().clearRestriction(id);
        return true;
    }

    private int writeArmor(ConfigurationSection kit, String slot, @Nullable ItemStack item,
                           Predicate<ItemStack> rejected) {
        if (isEmpty(item)) return 0;
        if (rejected.test(item)) return 1;
        codec.encode(kit.createSection("armor." + slot), item);
        return 0;
    }

    private static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
