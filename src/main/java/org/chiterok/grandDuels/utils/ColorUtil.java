package org.chiterok.grandDuels.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single entry point for turning configurable strings into Kyori {@link Component}s.
 * <p>
 * Supported syntax (can be mixed): legacy codes ({@code &a}, {@code &l}, {@code &r}),
 * hex colors ({@code &#RRGGBB}, {@code <#RRGGBB>}) and MiniMessage tags such as
 * {@code <gradient:#ff0000:#00ff00>text</gradient>}.
 * <p>
 * Placeholders are written as {@code {name}}. Placeholder values are treated as trusted plugin-side text
 * (player names, kit names, numbers) and may themselves contain legacy/hex codes.
 */
public final class ColorUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern AMP_HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern LEGACY = Pattern.compile("&([0-9a-fA-Fk-oK-OrR])");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");

    private ColorUtil() {}

    public static Component colorize(String text) {
        return colorize(text, Map.of());
    }

    public static Component colorize(String text, Map<String, String> placeholders) {
        return colorize(text, placeholders, null);
    }

    /**
     * Colorizes {@code text}; {@code {name}} placeholders are replaced first, then PlaceholderAPI placeholders are
     * resolved for {@code viewer} (skipped when {@code viewer} is {@code null} or PlaceholderAPI is not installed).
     */
    public static Component colorize(String text, Map<String, String> placeholders, @Nullable Player viewer) {
        if (text == null || text.isEmpty()) return Component.empty();
        String converted = toMiniMessage(text);
        if (!placeholders.isEmpty()) converted = applyPlaceholders(converted, placeholders);
        return MINI.deserialize(applyPapi(converted, viewer));
    }

    public static List<Component> colorize(List<String> lines) {
        return colorize(lines, Map.of());
    }

    public static List<Component> colorize(List<String> lines, Map<String, String> placeholders) {
        return colorize(lines, placeholders, null);
    }

    public static List<Component> colorize(List<String> lines, Map<String, String> placeholders, @Nullable Player viewer) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(colorize(line, placeholders, viewer));
        return out;
    }

    /** Colorizes item names/lore: italics are disabled unless the text explicitly enables them. */
    public static Component colorizeItem(String text) {
        return colorizeItem(text, Map.of());
    }

    public static Component colorizeItem(String text, Map<String, String> placeholders) {
        return colorizeItem(text, placeholders, null);
    }

    public static Component colorizeItem(String text, Map<String, String> placeholders, @Nullable Player viewer) {
        return colorize(text, placeholders, viewer).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> colorizeItem(List<String> lines) {
        return colorizeItem(lines, Map.of());
    }

    public static List<Component> colorizeItem(List<String> lines, Map<String, String> placeholders) {
        return colorizeItem(lines, placeholders, null);
    }

    public static List<Component> colorizeItem(List<String> lines, Map<String, String> placeholders, @Nullable Player viewer) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(colorizeItem(line, placeholders, viewer));
        return out;
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String applyPlaceholders(String text, Map<String, String> placeholders) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = placeholders.get(matcher.group(1));
            String replacement = value == null ? matcher.group() : toMiniMessage(value);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Resolves PlaceholderAPI placeholders in already MiniMessage-converted text. The resolved text may carry legacy
     * or hex codes, so it is converted again; text without any change is returned untouched.
     */
    private static String applyPapi(String text, @Nullable Player viewer) {
        if (viewer == null || text.indexOf('%') < 0) return text;
        String parsed = PapiParser.parse(viewer, text);
        return parsed.equals(text) ? text : toMiniMessage(parsed);
    }

    /** Converts legacy and {@code &#RRGGBB} syntax into MiniMessage tags. */
    private static String toMiniMessage(String text) {
        String withHex = AMP_HEX.matcher(text).replaceAll("<#$1>");
        Matcher matcher = LEGACY.matcher(withHex);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            matcher.appendReplacement(out, Matcher.quoteReplacement(legacyTag(code)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String legacyTag(char code) {
        return switch (code) {
            case '0' -> "<reset><black>";
            case '1' -> "<reset><dark_blue>";
            case '2' -> "<reset><dark_green>";
            case '3' -> "<reset><dark_aqua>";
            case '4' -> "<reset><dark_red>";
            case '5' -> "<reset><dark_purple>";
            case '6' -> "<reset><gold>";
            case '7' -> "<reset><gray>";
            case '8' -> "<reset><dark_gray>";
            case '9' -> "<reset><blue>";
            case 'a' -> "<reset><green>";
            case 'b' -> "<reset><aqua>";
            case 'c' -> "<reset><red>";
            case 'd' -> "<reset><light_purple>";
            case 'e' -> "<reset><yellow>";
            case 'f' -> "<reset><white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> "";
        };
    }

    /** Lower-cases using the root locale (avoids the Turkish-i problem for registry keys). */
    public static String key(String raw) {
        return raw.toLowerCase(Locale.ROOT).trim();
    }
}
