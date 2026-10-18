package org.chiterok.grandDuels.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.utils.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads messages.yml and renders it through {@link ColorUtil}. Whenever the recipient is a {@link Player}, PlaceholderAPI
 * placeholders ({@code %...%}) are resolved for them (no-op without PlaceholderAPI). Built-in {@code {placeholders}} are
 * replaced first.
 */
public final class Messages {

    private final ConfigManager configs;

    public Messages(ConfigManager configs) {
        this.configs = configs;
    }

    /** The PlaceholderAPI viewer for a recipient: the player itself, {@code null} for the console and others. */
    public static @Nullable Player viewerOf(@Nullable CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }

    /** Builds a placeholder map from alternating key/value arguments. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> ph(Object... keyValues) {
        // Tolerate a ready-made map passed as the only "varargs" element (a classic misuse of this API).
        if (keyValues.length == 1 && keyValues[0] instanceof Map<?, ?> ready) return (Map<String, String>) ready;
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("placeholders must be key/value pairs");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), String.valueOf(keyValues[i + 1]));
        }
        return map;
    }

    // ---------------------------------------------------------------- components

    public Component get(String path, Map<String, String> placeholders, @Nullable Player viewer) {
        String raw = configs.messages().getString(path);
        return raw == null ? missing(path) : ColorUtil.colorize(raw, placeholders, viewer);
    }

    public Component get(String path, Map<String, String> placeholders) {
        return get(path, placeholders, null);
    }

    public Component get(String path) {
        return get(path, Map.of(), null);
    }

    public Component prefixed(String path, Map<String, String> placeholders, @Nullable Player viewer) {
        String raw = configs.messages().getString(path);
        if (raw == null) return missing(path);
        String prefix = configs.messages().getString("prefix");
        return ColorUtil.colorize((prefix == null ? "" : prefix) + raw, placeholders, viewer);
    }

    public Component prefixed(String path, Map<String, String> placeholders) {
        return prefixed(path, placeholders, null);
    }

    public List<Component> lines(String path, Map<String, String> placeholders, @Nullable Player viewer) {
        List<Component> out = new ArrayList<>();
        for (String line : configs.messages().getStringList(path)) {
            out.add(ColorUtil.colorize(line, placeholders, viewer));
        }
        return out;
    }

    public List<Component> lines(String path, Map<String, String> placeholders) {
        return lines(path, placeholders, null);
    }

    /** Raw (uncolored) configured string, for callers that feed it into {@link ColorUtil} themselves. */
    public String string(String path) {
        // getString(path) honors the jar defaults; getString(path, fallback) would skip them for keys missing on disk
        String value = configs.messages().getString(path);
        return value != null ? value : "[missing: " + path + "]";
    }

    public List<String> stringList(String path) {
        return configs.messages().getStringList(path);
    }

    // ---------------------------------------------------------------- delivery (viewer = recipient)

    public void send(CommandSender target, String path, Object... keyValues) {
        target.sendMessage(prefixed(path, ph(keyValues), viewerOf(target)));
    }

    public void send(CommandSender target, String path, Map<String, String> placeholders) {
        target.sendMessage(prefixed(path, placeholders, viewerOf(target)));
    }

    public void sendPlain(CommandSender target, String path, Object... keyValues) {
        target.sendMessage(get(path, ph(keyValues), viewerOf(target)));
    }

    public void actionBar(Player player, String path, Map<String, String> placeholders) {
        player.sendActionBar(get(path, placeholders, player));
    }

    public void actionBar(Player player, String path, Object... keyValues) {
        player.sendActionBar(get(path, ph(keyValues), player));
    }

    /** Shows {@code titles.<name>.title} / {@code titles.<name>.subtitle}. Durations are in ticks. */
    public void title(Player player, String name, Map<String, String> placeholders,
                      int fadeInTicks, int stayTicks, int fadeOutTicks) {
        Component title = get("titles." + name + ".title", placeholders, player);
        Component subtitle = get("titles." + name + ".subtitle", placeholders, player);
        Title.Times times = Title.Times.times(ticks(fadeInTicks), ticks(stayTicks), ticks(fadeOutTicks));
        player.showTitle(Title.title(title, subtitle, times));
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    private static Component missing(String path) {
        return Component.text("[missing message: " + path + "]", NamedTextColor.RED);
    }
}
