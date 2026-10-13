package org.chiterok.grandDuels.utils;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves PlaceholderAPI {@code %identifier_params%} placeholders in menu text.
 * <p>
 * PlaceholderAPI is a soft dependency: every call into its classes is isolated in {@link Hook}, which is only loaded
 * after {@link #enable()} confirmed that the plugin is present. Without PlaceholderAPI {@link #parse} returns the text
 * unchanged.
 * <p>
 * PlaceholderAPI results may contain {@code §} color codes (including the {@code §x§r§r§g§g§b§b} hex form); they are
 * normalized to the {@code &} / {@code &#RRGGBB} syntax understood by {@link ColorUtil}.
 */
public final class PapiParser {

    private static final Pattern SECTION_HEX = Pattern.compile(
            "§x§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])§([0-9a-fA-F])");

    private static volatile boolean available;

    private PapiParser() {}

    /** Turns parsing on. Call only when the PlaceholderAPI plugin is installed. */
    public static void enable() {
        try {
            Hook.verify();
            available = true;
        } catch (LinkageError e) {
            available = false;
        }
    }

    public static void disable() {
        available = false;
    }

    public static boolean isAvailable() {
        return available;
    }

    /**
     * @return {@code text} with PlaceholderAPI placeholders resolved for {@code viewer}; unchanged when PlaceholderAPI
     * is missing, the viewer is {@code null} or the text has no placeholder.
     */
    public static String parse(@Nullable Player viewer, String text) {
        if (!available || viewer == null || text.indexOf('%') < 0) return text;
        try {
            return normalize(Hook.parse(viewer, text));
        } catch (LinkageError e) {
            available = false;
            return text;
        }
    }

    /** Converts {@code §} formatting into the {@code &} syntax so that ColorUtil can handle it. */
    private static String normalize(String text) {
        if (text.indexOf('§') < 0) return text;
        Matcher matcher = SECTION_HEX.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            StringBuilder hex = new StringBuilder("&#");
            for (int group = 1; group <= 6; group++) hex.append(matcher.group(group));
            matcher.appendReplacement(out, Matcher.quoteReplacement(hex.toString()));
        }
        matcher.appendTail(out);
        return out.toString().replace('§', '&');
    }

    /** Only touched when PlaceholderAPI is installed, so its classes are never loaded otherwise. */
    private static final class Hook {

        private Hook() {}

        static void verify() {
            PlaceholderAPI.isRegistered("player");
        }

        static String parse(Player viewer, String text) {
            return PlaceholderAPI.setPlaceholders(viewer, text);
        }
    }
}
