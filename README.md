# GrandDuels

Modern 1v1 dueling plugin for **Paper 1.21.11** (Java 21): data-component kits, configurable PvP cooldowns, ELO-ranked matchmaking, player-made kits and automatic arena rollback.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Paper](https://img.shields.io/badge/Paper-1.21.11-blue)
![Java](https://img.shields.io/badge/Java-21-orange)

## Features

- **Duel requests**: `/duel <player>` opens a kit selector, then a settings screen. The target accepts or denies with clickable chat buttons.
- **Ranked and unranked queues**: `/ranked` and `/unranked` menus with live player counts. Ranked uses ELO matchmaking, and the allowed rating gap widens the longer a player waits.
- **Kits** defined in `kits.yml`, with full support for 1.21+ data components (food, consumable, max stack size, armor trims, attribute modifiers, potions, effects). Admins can create and edit kits in-game.
- **Player kits**: players can save their own kits (limit configurable per player).
- **Per-player PvP rules**: cooldowns and bans for golden apples, enchanted golden apples, tridents, wind charges, ender pearls, chorus fruit and fireworks, plus elytra and riptide bans. Edited in `/duel cooldowns` and saved per player.
- **Modern combat tuning**: optional damage cap and knockback multiplier for mace smash and spear charge.
- **Safety first**: pre-duel inventory, effects, health and location are snapshotted to disk, so a crash or reload never destroys a player's items. They are restored on the next join.
- **Arena handling**: arena groups of interchangeable two-point sub-arenas (a free one is picked per duel, groups/arenas can be switched off for maintenance) with state tracking (`WAITING`, `STARTING`, `IN_GAME`, `RESETTING`), arena boundaries, and block rollback when building is enabled.
- **Combat restrictions**: command blocking (with whitelist), item drop/pickup blocking, frozen countdown, ender pearl and chorus fruit boundary checks.
- **Stats**: wins, losses, kills, deaths, K/D, win streaks and ELO, stored asynchronously in YAML or SQLite.
- **Fully configurable text**: `messages.yml` and every menu in `menu/*.yml`. Colors support legacy codes (`&a`), hex (`&#RRGGBB`, `<#RRGGBB>`) and MiniMessage gradients.
- **PlaceholderAPI in menus**: with PlaceholderAPI installed, any `%placeholder%` in menu titles, item names and lore is resolved for the player viewing the menu (e.g. `%player_name%`, `%vault_eco_balance%`). Built-in `{placeholders}` are replaced first, so `%grandduels_elo_{target}%`-style combinations work.

## Requirements

- Paper 1.21.11 (or a compatible build)
- Java 21
- PlaceholderAPI (optional, for `%placeholders%` in menus and the `%grandduels_...%` expansion)

## Installation

1. Download the jar (or build it, see below) and put it in your server's `plugins/` folder.
2. Start the server once to generate the config files in `plugins/GrandDuels/`.
3. Create at least one arena and one kit (see [Quick setup](#quick-setup)).
4. Optionally run `/duels setlobby` and set `duel.return-mode: LOBBY` in `config.yml`.

## Quick setup

```text
/duels arena group create castle   # players pick groups; a free arena of the group is used
/duels arena add castle castle-1   # add a sub-arena to the group
/duels arena pos1 castle castle-1  # stand on the first spawn point
/duels arena pos2 castle castle-1  # stand on the second spawn point (same world)
/duels kit create mykit       # you get creative mode and an empty loadout
/duels kit save               # saves the loadout and restores your own inventory
```

A sample `nodebuff` kit ships in `kits.yml`.

## Commands

### Players

| Command | Description |
|---|---|
| `/duel <player>` | Challenge a player (opens the kit selector) |
| `/duel accept [player]` | Accept a pending request |
| `/duel deny [player]` | Deny a pending request |
| `/duel cancel` | Cancel your outgoing request |
| `/duel cooldowns` | Edit your personal cooldowns and restrictions |
| `/duel kit <create\|save\|delete\|list> [name]` | Manage your own kits |
| `/duel leave` | Leave the ranked/unranked queue |
| `/duel stats [player]` | Show duel statistics |
| `/ranked` | Open the ranked queue menu |
| `/unranked` | Open the unranked queue menu |

### Admins (`/duels`, aliases `/gduels`, `/grandduels`)

| Command | Description |
|---|---|
| `/duels arena group <create\|delete\|setname\|seticon\|toggle> <group>` | Manage arena groups; `toggle` switches a whole group on/off |
| `/duels arena <add\|remove\|pos1\|pos2\|toggle> <group> <arena>` | Manage the sub-arenas of a group; `toggle` switches one arena on/off (maintenance) |
| `/duels arena kits` | Choose which groups each standard kit can be played on |
| `/duels kit <create\|edit\|save\|delete\|cancel> [name]` | Manage standard kits in-game |
| `/duels setlobby` | Set the lobby location |
| `/duels forceend <player>` | End a player's duel as a draw |
| `/duels reload` | Reload config, messages, kits and menus |

## Permissions

| Permission | Default | Description |
|---|---|---|
| `grandduels.duel` | everyone | Send, accept and deny duel requests |
| `grandduels.stats` | everyone | View statistics |
| `grandduels.admin` | op | All admin commands |
| `grandduels.bypass.commands` | op | Use any command during a duel |
| `grandduels.kits.unlimited` | none | Ignore the player kit limit |

Additional nodes used by the queue and player-kit commands are `grandduels.ranked`, `grandduels.unranked`, `grandduels.queue` and `grandduels.kits`.

## Configuration

| File | Purpose |
|---|---|
| `config.yml` | Storage type, countdown, timings, return mode, boundaries, building, matchmaking, player-kit limits |
| `messages.yml` | All messages, titles and the duel scoreboard |
| `kits.yml` | Kit definitions |
| `menu/*.yml` | Layout and text of every menu, including default cooldown values (`menu/cooldowns.yml`) |
| `arenas.yml` | Arena data (generated, edit through commands) |

Storage is `YAML` by default; set `storage.type: SQLITE` and restart to use SQLite.

## Building

```bash
mvn clean package
```

The shaded jar is created in `target/`. Maven needs access to the PaperMC repository (`https://repo.papermc.io/repository/maven-public/`).

## Project structure

```text
org.chiterok.grandDuels
├── arena/      arena model, manager, state and block rollback
├── command/    command registry; admin/ and player/ sub-commands
├── config/     ConfigManager, Settings, Messages, YamlFile
├── cooldown/   PvPCooldownManager, rule types and defaults
├── data/       stats storage (YAML/SQLite), preferences, snapshots
├── gui/        menu framework and menus
├── kit/        kits, item codec, player kits, kit editing
├── match/      matches, requests, queues, scoreboard, ELO flow
├── runtime/    event listeners
└── utils/      ColorUtil, TimeUtil, ItemBuilder, StoredLocation
```

## License

GrandDuels is released under the [MIT License](LICENSE). Copyright (c) 2026 chiterok.
