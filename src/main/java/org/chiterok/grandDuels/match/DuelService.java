package org.chiterok.grandDuels.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaGroup;
import org.chiterok.grandDuels.gui.KitSelectorGUI;
import org.chiterok.grandDuels.kit.Kit;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Validation and orchestration of the request -> accept -> match flow. Shared by commands and GUIs. */
public final class DuelService {

    private final ClaudeDuels plugin;

    public DuelService(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    /** {@code /duel <player>}: opens the kit selector or sends a request with the default kit. */
    public void challenge(Player sender, Player target) {
        if (!validateParticipants(sender, target)) return;
        if (plugin.kits().all().isEmpty()) {
            plugin.messages().send(sender, "kits.none-available");
            return;
        }
        if (plugin.settings().duel().useKitSelector()) {
            new KitSelectorGUI(plugin, sender, target, 0).open();
            return;
        }
        Kit kit = plugin.kits().get(plugin.settings().duel().defaultKit());
        if (kit == null) kit = plugin.kits().all().iterator().next();
        sendRequest(sender, target, kit, plugin.preferences().get(sender.getUniqueId()).toSettings(plugin.menus().defaults()),
                null);
    }

    /** @param arenaId the arena the sender picked, or {@code null} for any free one (checked against the kit) */
    public void sendRequest(Player sender, Player target, Kit kit, MatchSettings settings, @Nullable String arenaId) {
        if (!validateParticipants(sender, target)) return;
        String arena = arenaId;
        if (arena != null) {
            ArenaGroup chosen = plugin.arenas().group(arena);
            if (chosen == null || !chosen.isPlayable() || !plugin.arenas().isAllowed(kit, chosen)) {
                plugin.messages().send(sender, "arena.not-allowed", "arena", arena);
                return;
            }
            // the menu may have been opened a while ago: the picked arena must be free right now
            if (chosen.isBusy()) {
                plugin.messages().send(sender, "arena.chosen-unavailable");
                return;
            }
        }
        if (plugin.requests().send(sender, target, kit, settings, arena) == RequestManager.SendResult.ALREADY_PENDING) {
            plugin.messages().send(sender, "duels.already-sent");
        }
    }

    private boolean validateParticipants(Player sender, Player target) {
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            plugin.messages().send(sender, "duels.cannot-self");
            return false;
        }
        if (plugin.kitEdits().isEditing(sender.getUniqueId())) {
            plugin.messages().send(sender, "kits.editing-busy");
            return false;
        }
        if (plugin.arenaMode().isIn(sender.getUniqueId())) {
            plugin.messages().send(sender, "arenamode.busy");
            return false;
        }
        if (plugin.spectators().isSpectating(sender.getUniqueId())) {
            plugin.messages().send(sender, "spectate.self-spectating");
            return false;
        }
        if (plugin.spectators().isSpectating(target.getUniqueId())) {
            plugin.messages().send(sender, "spectate.target-spectating", "player", target.getName());
            return false;
        }
        if (plugin.kitEdits().isEditing(target.getUniqueId()) || plugin.arenaMode().isIn(target.getUniqueId())) {
            plugin.messages().send(sender, "duels.target-busy", "player", target.getName());
            return false;
        }
        if (plugin.matches().isInMatch(sender.getUniqueId())) {
            plugin.messages().send(sender, "duels.self-busy");
            return false;
        }
        if (plugin.matches().isInMatch(target.getUniqueId())) {
            plugin.messages().send(sender, "duels.target-busy", "player", target.getName());
            return false;
        }
        return true;
    }

    public void accept(Player target, @Nullable String senderName) {
        Optional<DuelRequest> chosen = select(target, senderName);
        if (chosen.isEmpty()) return;
        DuelRequest request = chosen.get();

        Player sender = Bukkit.getPlayer(request.senderId());
        if (sender == null) {
            plugin.requests().consume(request.senderId());
            plugin.messages().send(target, "general.player-not-found", "player", request.senderName());
            return;
        }
        Kit kit = request.kit();

        plugin.requests().consume(request.senderId());
        MatchManager.StartResult result = plugin.matches().start(sender, target, kit, request.settings(),
                DuelMode.FRIENDLY, request.arena());
        switch (result) {
            case STARTED -> { /* Match sends its own messages */ }
            case PLAYER_BUSY -> {
                plugin.messages().send(target, "duels.player-busy");
                plugin.messages().send(sender, "duels.player-busy");
            }
            case NO_ARENA -> {
                plugin.messages().send(target, "arena.none-available");
                plugin.messages().send(sender, "arena.none-available");
            }
            case ARENA_UNAVAILABLE -> {
                plugin.messages().send(target, "arena.chosen-unavailable");
                plugin.messages().send(sender, "arena.chosen-unavailable");
            }
            case FAILED -> {
                plugin.messages().send(target, "duels.start-failed");
                plugin.messages().send(sender, "duels.start-failed");
            }
        }
    }

    public void deny(Player target, @Nullable String senderName) {
        Optional<DuelRequest> chosen = select(target, senderName);
        if (chosen.isEmpty()) return;
        DuelRequest request = chosen.get();
        plugin.requests().consume(request.senderId());
        plugin.messages().send(target, "duels.decline-confirm", "player", request.senderName());
        Player sender = Bukkit.getPlayer(request.senderId());
        if (sender != null) plugin.messages().send(sender, "duels.declined", "player", target.getName());
    }

    public void cancel(Player sender) {
        Optional<DuelRequest> request = plugin.requests().consume(sender.getUniqueId());
        if (request.isEmpty()) {
            plugin.messages().send(sender, "duels.cancel-none");
            return;
        }
        plugin.messages().send(sender, "duels.cancelled", "target", request.get().targetName());
        Player target = Bukkit.getPlayer(request.get().targetId());
        if (target != null) plugin.messages().send(target, "duels.request-cancelled-target", "sender", sender.getName());
    }

    /** Resolves which pending request the target means; sends the appropriate error message otherwise. */
    private Optional<DuelRequest> select(Player target, @Nullable String senderName) {
        List<DuelRequest> incoming = plugin.requests().incoming(target.getUniqueId());
        if (incoming.isEmpty()) {
            plugin.messages().send(target, "duels.none-pending");
            return Optional.empty();
        }
        if (senderName != null) {
            for (DuelRequest request : incoming) {
                if (request.senderName().equalsIgnoreCase(senderName)) return Optional.of(request);
            }
            plugin.messages().send(target, "duels.request-not-found");
            return Optional.empty();
        }
        if (incoming.size() > 1) {
            plugin.messages().send(target, "duels.multiple-pending", "players",
                    incoming.stream().map(DuelRequest::senderName).collect(Collectors.joining(", ")));
            return Optional.empty();
        }
        return Optional.of(incoming.get(0));
    }
}
