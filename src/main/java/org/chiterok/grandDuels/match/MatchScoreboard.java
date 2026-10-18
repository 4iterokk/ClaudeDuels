package org.chiterok.grandDuels.match;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.utils.ColorUtil;
import org.chiterok.grandDuels.utils.PapiParser;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The duel scoreboard: a sidebar built from messages.yml and a health objective below the nameplates. It uses the plain
 * Bukkit scoreboard API (teams as line carriers), so no packet library is required.
 * <p>
 * Two modes, chosen once per duel:
 * <ul>
 *   <li><b>Shared</b> (default): one board for every viewer. The vanilla {@code HEALTH} criterion only updates scores
 *   of players that use the board, so a shared board is required for it.</li>
 *   <li><b>Personal</b>: used when PlaceholderAPI is installed and {@code scoreboard.title} / {@code scoreboard.lines}
 *   contain a {@code %placeholder%}. Every viewer (duelists and spectators) gets a board of their own so the
 *   placeholders resolve for them. The health under the nameplates is then a {@code DUMMY} objective that is kept in
 *   sync from the duelists' health (see {@link #refreshHealth}).</li>
 * </ul>
 * Main thread only.
 */
public final class MatchScoreboard {

    private static final int MAX_LINES = 15;

    /** One scoreboard with its sidebar lines and health objective. */
    private static final class Panel {
        private final Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        private final Objective sidebar;
        private final Objective health;
        private final List<Team> lineTeams = new ArrayList<>();

        private Panel(int lineCount, boolean manualHealth) {
            sidebar = board.registerNewObjective("gd_sidebar", Criteria.DUMMY, Component.empty());
            sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
            sidebar.numberFormat(NumberFormat.blank());

            health = board.registerNewObjective("gd_health", manualHealth ? Criteria.DUMMY : Criteria.HEALTH,
                    Component.text("\u2764", NamedTextColor.RED));
            health.setDisplaySlot(DisplaySlot.BELOW_NAME);

            for (int i = 0; i < lineCount; i++) {
                String entry = "\u00a7" + Integer.toHexString(i) + "\u00a7r";
                Team team = board.registerNewTeam("gd_line_" + i);
                team.addEntry(entry);
                sidebar.getScore(entry).setScore(lineCount - i);
                lineTeams.add(team);
            }
        }

        private void render(String titleTemplate, List<String> templates, Map<String, String> placeholders,
                            @Nullable Player viewer) {
            sidebar.displayName(ColorUtil.colorize(titleTemplate, placeholders, viewer));
            for (int i = 0; i < templates.size(); i++) {
                lineTeams.get(i).prefix(ColorUtil.colorize(templates.get(i), placeholders, viewer));
            }
        }

        private void setHealth(Map<String, Integer> values) {
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                health.getScore(entry.getKey()).setScore(entry.getValue());
            }
        }
    }

    private final String titleTemplate;
    private final List<String> templates;
    private final boolean personal;
    private final @Nullable Panel shared;
    private final Map<UUID, Panel> panels = new HashMap<>();
    private Map<String, String> lastPlaceholders = Map.of();
    private Map<String, Integer> lastHealth = Map.of();

    public MatchScoreboard(Messages messages) {
        this.titleTemplate = messages.string("scoreboard.title");
        List<String> configured = messages.stringList("scoreboard.lines");
        this.templates = List.copyOf(configured.size() > MAX_LINES ? configured.subList(0, MAX_LINES) : configured);
        this.personal = PapiParser.isAvailable() && usesPlaceholderApi();
        this.shared = personal ? null : new Panel(templates.size(), false);
        if (shared != null) shared.render(titleTemplate, templates, Map.of(), null);
    }

    private boolean usesPlaceholderApi() {
        if (titleTemplate.indexOf('%') >= 0) return true;
        for (String line : templates) {
            if (line.indexOf('%') >= 0) return true;
        }
        return false;
    }

    /** True when every viewer has a board of their own (PlaceholderAPI placeholders in the scoreboard). */
    public boolean isPersonal() {
        return personal;
    }

    /** Shows the scoreboard to a duelist or spectator. Their own board is restored by their snapshot. */
    public void show(Player player) {
        if (shared != null) {
            player.setScoreboard(shared.board);
            return;
        }
        Panel panel = new Panel(templates.size(), true);
        panels.put(player.getUniqueId(), panel);
        panel.render(titleTemplate, templates, lastPlaceholders, player);
        panel.setHealth(lastHealth);
        player.setScoreboard(panel.board);
    }

    /**
     * Refreshes the text of every board. {@code duelists} are the players whose health is displayed under the
     * nameplates (only needed in personal mode).
     */
    public void update(Map<String, String> placeholders, Collection<? extends Player> duelists) {
        this.lastPlaceholders = placeholders;
        if (shared != null) {
            shared.render(titleTemplate, templates, placeholders, null);
            return;
        }
        this.lastHealth = healthOf(duelists);
        Iterator<Map.Entry<UUID, Panel>> it = panels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Panel> entry = it.next();
            Player viewer = viewerOf(entry);
            if (viewer == null) {
                it.remove();
                continue;
            }
            entry.getValue().render(titleTemplate, templates, placeholders, viewer);
            entry.getValue().setHealth(lastHealth);
        }
    }

    /** Syncs only the health under the nameplates (personal mode; the shared board uses the vanilla criterion). */
    public void refreshHealth(Collection<? extends Player> duelists) {
        if (shared != null) return;
        this.lastHealth = healthOf(duelists);
        Iterator<Map.Entry<UUID, Panel>> it = panels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Panel> entry = it.next();
            if (viewerOf(entry) == null) it.remove();
            else entry.getValue().setHealth(lastHealth);
        }
    }

    /** The viewer of a panel, or {@code null} when they left or switched to another scoreboard (spectate ended). */
    private static @Nullable Player viewerOf(Map.Entry<UUID, Panel> entry) {
        Player player = Bukkit.getPlayer(entry.getKey());
        return player != null && player.isOnline() && player.getScoreboard() == entry.getValue().board ? player : null;
    }

    private static Map<String, Integer> healthOf(Collection<? extends Player> duelists) {
        Map<String, Integer> values = new HashMap<>();
        for (Player duelist : duelists) values.put(duelist.getName(), (int) Math.ceil(Math.max(0.0, duelist.getHealth())));
        return values;
    }
}
