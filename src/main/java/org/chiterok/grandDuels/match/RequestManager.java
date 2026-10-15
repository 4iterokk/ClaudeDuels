package org.chiterok.grandDuels.match;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaGroup;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.cooldown.RuleType;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.utils.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Pending duel requests. At most one outgoing request per sender. Main thread only. */
public final class RequestManager {

    public enum SendResult { SENT, ALREADY_PENDING }

    private final ClaudeDuels plugin;
    private final Map<UUID, DuelRequest> bySender = new LinkedHashMap<>();

    public RequestManager(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    public SendResult send(Player sender, Player target, Kit kit, MatchSettings settings, @Nullable String arenaId) {
        if (bySender.containsKey(sender.getUniqueId())) return SendResult.ALREADY_PENDING;

        int seconds = plugin.settings().duel().requestExpireSeconds();
        UUID senderId = sender.getUniqueId();
        BukkitTask expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(senderId), seconds * 20L);
        DuelRequest request = new DuelRequest(senderId, sender.getName(), target.getUniqueId(), target.getName(),
                kit, settings, arenaId, expiry);
        bySender.put(senderId, request);

        Messages messages = plugin.messages();
        messages.send(sender, "duels.request-sent", "target", target.getName(), "kit", kit.displayName(),
                "arena", arenaName(arenaId), "seconds", seconds);
        target.sendMessage(requestComponent(request, kit, seconds));
        return SendResult.SENT;
    }

    /** Display name of the chosen arena, or the localized "random" text. */
    private String arenaName(@Nullable String arenaId) {
        ArenaGroup arena = arenaId == null ? null : plugin.arenas().group(arenaId);
        return arena == null ? plugin.messages().string("duels.arena-random") : arena.displayName();
    }

    /**
     * The request line carries a hover with the complete rules of the duel: kit, arena, whether custom cooldowns are
     * on, and the cooldown / ban of every rule. The accept and deny buttons keep their own hover texts.
     */
    private Component requestComponent(DuelRequest request, Kit kit, int seconds) {
        Messages m = plugin.messages();
        Map<String, String> ph = Messages.ph(
                "sender", request.senderName(), "kit", kit.displayName(), "seconds", seconds,
                "arena", arenaName(request.arena()),
                "cooldowns", plainState(request.settings().customCooldowns()));
        Component text = m.prefixed("duels.request-received", ph)
                .hoverEvent(HoverEvent.showText(rulesHover(request.settings(), ph)));
        Component accept = m.get("duels.request-accept-button")
                .clickEvent(ClickEvent.runCommand("/duel accept " + request.senderName()))
                .hoverEvent(HoverEvent.showText(m.get("duels.request-accept-hover")));
        Component deny = m.get("duels.request-deny-button")
                .clickEvent(ClickEvent.runCommand("/duel deny " + request.senderName()))
                .hoverEvent(HoverEvent.showText(m.get("duels.request-deny-hover")));
        return Component.empty().append(text).append(Component.newline())
                .append(accept).append(Component.space()).append(deny);
    }

    /** {@code duels.request-hover} with its {@code {rules}} line replaced by one line per rule. */
    private Component rulesHover(MatchSettings settings, Map<String, String> ph) {
        List<Component> lines = new ArrayList<>();
        for (String template : plugin.messages().stringList("duels.request-hover")) {
            if (template.trim().equals("{rules}")) {
                lines.addAll(ruleLines(settings));
            } else {
                lines.add(ColorUtil.colorize(template, ph));
            }
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    /** One line per {@link RuleType}: its cooldown, "banned", "no limits" or "vanilla" (custom cooldowns off). */
    private List<Component> ruleLines(MatchSettings settings) {
        List<Component> lines = new ArrayList<>();
        for (RuleType type : RuleType.values()) {
            String name = plugin.messages().string("cooldown-names." + type.configKey());
            String template;
            Map<String, String> ph = new java.util.HashMap<>();
            ph.put("rule", name);
            if (settings.isBanned(type)) {
                template = "duels.rule-banned";
            } else if (!type.hasCooldown()) {
                template = "duels.rule-allowed";
            } else if (!settings.customCooldowns()) {
                template = "duels.rule-vanilla";
            } else if (settings.cooldownSeconds(type) > 0.0) {
                double seconds = settings.cooldownSeconds(type);
                ph.put("seconds", seconds == Math.rint(seconds) ? String.valueOf((long) seconds)
                        : String.format(java.util.Locale.ROOT, "%.1f", seconds));
                template = "duels.rule-cooldown";
            } else {
                template = "duels.rule-allowed";
            }
            lines.add(plugin.messages().get(template, ph));
        }
        return lines;
    }

    private String plainState(boolean on) {
        return plugin.messages().string(on ? "duels.state-on" : "duels.state-off");
    }

    private void expire(UUID senderId) {
        DuelRequest request = bySender.remove(senderId);
        if (request == null) return;
        Player sender = Bukkit.getPlayer(request.senderId());
        Player target = Bukkit.getPlayer(request.targetId());
        if (sender != null) plugin.messages().send(sender, "duels.request-expired", "target", request.targetName());
        if (target != null) {
            plugin.messages().send(target, "duels.request-expired-target", "sender", request.senderName());
        }
    }

    public Optional<DuelRequest> outgoing(UUID senderId) {
        return Optional.ofNullable(bySender.get(senderId));
    }

    public List<DuelRequest> incoming(UUID targetId) {
        List<DuelRequest> list = new ArrayList<>();
        for (DuelRequest request : bySender.values()) {
            if (request.targetId().equals(targetId)) list.add(request);
        }
        return list;
    }

    /** Removes and returns the request of {@code senderId}, cancelling its expiry. */
    public Optional<DuelRequest> consume(UUID senderId) {
        DuelRequest request = bySender.remove(senderId);
        if (request == null) return Optional.empty();
        request.expiryTask().cancel();
        return Optional.of(request);
    }

    /** Drops every request involving the player (quit, duel start). */
    public void purge(UUID playerId) {
        List<UUID> senders = new ArrayList<>();
        for (DuelRequest request : bySender.values()) {
            if (request.senderId().equals(playerId) || request.targetId().equals(playerId)) {
                senders.add(request.senderId());
            }
        }
        for (UUID sender : senders) consume(sender);
    }

    public void clear() {
        for (DuelRequest request : bySender.values()) request.expiryTask().cancel();
        bySender.clear();
    }
}
