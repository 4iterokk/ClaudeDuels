package org.chiterok.grandDuels.kit;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.PotionContents;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides which items may end up in a player-made kit that was built in the creative editor.
 * <p>
 * Creative mode hands the client the power to put <i>any</i> item into <i>any</i> slot, and a modified client can send
 * items that the vanilla creative menu never offers (over-enchanted gear, custom attributes, items with a custom
 * stack size or food component, containers full of items, ...). Nothing the client sends is trusted: every item is
 * validated here when the kit is saved (and, for early feedback, when it enters the inventory).
 * <p>
 * The rules are an allow-list for item data: an item is rejected as soon as it carries a data component that is not
 * known to be harmless, so components added by future Minecraft versions are rejected by default.
 */
public final class PlayerKitItemFilter implements Predicate<ItemStack> {

    /** Items that are never allowed in player kits, independent of config.yml. */
    private static final Set<String> ALWAYS_BANNED = Set.of(
            "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
            "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW", "TEST_BLOCK", "TEST_INSTANCE_BLOCK",
            "BARRIER", "LIGHT", "DEBUG_STICK", "KNOWLEDGE_BOOK", "BEDROCK", "SPAWNER", "TRIAL_SPAWNER", "VAULT",
            "END_PORTAL_FRAME", "END_PORTAL", "END_GATEWAY", "NETHER_PORTAL", "REINFORCED_DEEPSLATE",
            "WRITTEN_BOOK", "WRITABLE_BOOK");

    /** Data components (namespaced keys) that may be customized on an item; everything else is rejected. */
    private static final Set<String> ALLOWED_COMPONENTS = Set.of(
            "minecraft:enchantments", "minecraft:stored_enchantments", "minecraft:damage", "minecraft:repair_cost",
            "minecraft:custom_name", "minecraft:lore", "minecraft:trim", "minecraft:potion_contents",
            "minecraft:dyed_color", "minecraft:banner_patterns", "minecraft:base_color", "minecraft:fireworks",
            "minecraft:firework_explosion", "minecraft:charged_projectiles", "minecraft:instrument",
            "minecraft:profile", "minecraft:map_color");

    private final Set<Material> configBanned;
    private final int maxItemBytes;

    /**
     * @param configBanned materials banned in config.yml ({@code player-kits.banned-materials})
     * @param maxItemBytes serialized size limit per item, 0 = unlimited
     */
    public PlayerKitItemFilter(Set<Material> configBanned, int maxItemBytes) {
        this.configBanned = configBanned;
        this.maxItemBytes = maxItemBytes;
    }

    /** @return true if the item must NOT be saved into a player kit. */
    @Override
    public boolean test(ItemStack item) {
        return reason(item) != null;
    }

    /** Short machine-readable reason why the item is rejected, or {@code null} when it is fine. */
    public @Nullable String reason(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        Material type = item.getType();
        if (!type.isItem()) return "not-an-item";
        if (configBanned.contains(type) || ALWAYS_BANNED.contains(type.name())
                || type.name().endsWith("_SPAWN_EGG")) {
            return "banned-material";
        }
        if (item.getAmount() < 1 || item.getAmount() > item.getMaxStackSize()) return "amount";

        for (DataComponentType component : item.getDataTypes()) {
            if (!item.isDataOverridden(component)) continue;
            if (!ALLOWED_COMPONENTS.contains(component.key().asString())) return "component:" + component.key().asString();
        }
        if (item.hasData(DataComponentTypes.CONTAINER) && !item.getData(DataComponentTypes.CONTAINER).contents().isEmpty()) {
            return "container";
        }
        PotionContents potion = item.getData(DataComponentTypes.POTION_CONTENTS);
        if (potion != null && !potion.customEffects().isEmpty()) return "custom-potion-effects";

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String enchantReason = enchantmentReason(item, meta.getEnchants());
            if (enchantReason != null) return enchantReason;
            if (meta instanceof EnchantmentStorageMeta storage) {
                enchantReason = enchantmentReason(item, storage.getStoredEnchants());
                if (enchantReason != null) return enchantReason;
            }
        }

        if (maxItemBytes > 0 && item.serializeAsBytes().length > maxItemBytes) return "too-large";
        return null;
    }

    /** Enchantments must exist at a legal level, fit the item and not contradict each other (vanilla rules). */
    private static @Nullable String enchantmentReason(ItemStack item, Map<Enchantment, Integer> enchantments) {
        boolean book = item.getType() == Material.ENCHANTED_BOOK || item.getType() == Material.BOOK;
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            Enchantment enchantment = entry.getKey();
            int level = entry.getValue();
            if (level < 1 || level > enchantment.getMaxLevel()) return "enchantment-level";
            if (!book && !enchantment.canEnchantItem(item)) return "enchantment-item";
            for (Enchantment other : enchantments.keySet()) {
                if (other != enchantment && enchantment.conflictsWith(other)) return "enchantment-conflict";
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "PlayerKitItemFilter" + String.format(Locale.ROOT, "[maxItemBytes=%d]", maxItemBytes);
    }
}
