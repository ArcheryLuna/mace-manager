# MaceManager

Control how many maces exist on your Minecraft server and how often players can
use them. MaceManager tracks mace ownership, enforces a configurable server-wide
limit, and applies cooldowns based on mace and armor enchantments.

## Features

- **Server-wide mace limit:** unlimited by default. Set `allowed-maces` to a positive number to cap tracked maces. Offline owners and stored maces still count toward a cap.
- **SQLite ownership tracking:** keep track of holders and dropped maces across
  restarts in a local database, with automatic import from older YAML registries.
- **Duplicate enforcement:** audit loaded player inventories, storage, displays,
  and ground items, removing illegal maces and duplicate tracked identities.
- **Storage controls:** allow or block each supported storage type independently
  of the mace cap. Ender chests, shelves, and both frame types are allowed by
  default. Stored/displayed maces retain their recorded owners.
- **Configurable combat cooldowns:** off by default, matching vanilla. Turn `cooldown.enabled` on and set `base-seconds` to add a delay, then add or subtract seconds for enchantments on the mace and each worn armor piece.
- **Optional wind-charge cooldown:** configure a separate delay between launches.
- **Enchanting controls:** toggle mace access to anvils and enchanting tables.
- **Administration:** list holders, change limits, remove tracked maces, and issue
  special maces that do not count toward the limit.
- **Custom messages:** edit broadcasts, feedback, and action-bar messages in YAML.

## Requirements and compatibility

- Java 21.
- A Minecraft server providing the Bukkit/Spigot plugin API. The default build
  targets Paper 1.21.4 and aims to preserve Bukkit/Spigot compatibility.
- LuckPerms is optional; see [Permissions](#permissions) for its effect.

The code compiles and the regression suite passes against both the Paper 1.21.4
and 1.21.11 APIs. Listener tests use mocked server objects. Live Minecraft server
behavior, actual inventory transactions, and compatibility with other plugins have
not been verified by those checks. Bukkit/Spigot compatibility also requires
validation on those server implementations.

## Installation

1. Build the plugin using the instructions below, or obtain a trusted prebuilt JAR.
2. Stop your server and place `MaceManager-1.0.1.jar` in its `plugins/` folder.
3. Start the server to generate `plugins/MaceManager/config.yml`.
4. Edit the settings, then run `/maces reload` to apply them. Changes to
   `performance.async-registry-writes` require a restart.
5. Run `/maces` as an authorized administrator to view tracked maces.

**Existing maces:** ordinary maces created before installation or through vanilla
`/give` do not have a registered MaceManager identity and can be removed by cleanup.
Back up an existing world before enabling enforcement. Use
`/maces giveuntracked` when an administrator needs a mace outside the tracked limit.

## Tracked mace storage

Tracking records are stored in `plugins/MaceManager/maces.db`, a local SQLite
database. No external database server or credentials are needed; the JDBC driver
and native libraries are bundled in the plugin JAR. Configuration and messages
remain in `config.yml`.

Each tracked mace keeps its UUID in persistent item metadata. The database stores
that UUID, the holder's UUID and last known name, and the world/chunk coordinates
when dropped. It does not store or restore the physical item, enchantments, or
player inventory. Those remain part of Minecraft's world/player data.

The registry model also supports stored and displayed locations separately from
the recorded owner. These locations retain a storage type and either block
coordinates, an entity UUID with its last known world/chunk, or a player UUID for
personal storage such as an ender chest. Loading records needs no online player,
loaded world, or loaded chunk. Moving a record to storage preserves its owner;
actual player possession updates ownership and marks it carried.

Placement and cleanup use the same `mace-storage` rules. Viewing storage does not
transfer ownership. Taking a mace into a player's inventory or cursor does;
portable containers retain the mace's recorded owner while carrying it inside.

Existing SQLite databases upgrade transactionally to schema version 3 on startup,
preserving ownership and locations and adding permanent administrative revocations.
Back up before upgrading; older schema-version-1/2 releases cannot open the upgraded database.

Changes update the in-memory registry immediately and are batched for a
transactional save on the next tick. Failed saves retain dirty state for retries.
Shutdown drains pending writes and attempts to save the latest state. Optional
`performance.async-registry-writes` uses one ordered background writer.

### Upgrading from maces.yml

1. Stop the server and back up its world/player data and MaceManager folder.
2. Install the updated JAR, keeping the existing `maces.yml` in the plugin folder.
3. Start the server. The database is initialized and imports all legacy records in
   one transaction before tracking or cleanup listeners start.

The old YAML file is left untouched as an import source. After successful
initialization, SQLite is authoritative; editing `maces.yml` has no effect. Even an
empty database is marked initialized, preventing stale YAML records from being
imported again. Removing the database would discard this marker and allow the old
file to be imported on the next startup.

If migration or database loading fails, plugin startup aborts before enforcement
begins. Correct the logged problem and restart; there is no fallback to an empty
registry. For backups, stop the server cleanly and copy the plugin data folder
alongside the corresponding world/player data. Downgrading to a YAML-based release
requires a separate export; the retained YAML is not updated after migration.

## Configuration

The complete defaults, comments, and message keys are in
[`config.yml`](src/main/resources/config.yml). This example shows the main options
with their default values:

```yaml
allowed-maces: -1
allow-mace-enchanting: true

cooldown:
  enabled: false
  ignore-thorns: true
  base-seconds: 0
  minimum-seconds: 0
  maximum-seconds: 600
  mace-enchantments:
    density: 0
    breach: 0
    wind_burst: 0
  armor-enchantments:
    protection: 0
    blast_protection: 0
    projectile_protection: 0
    fire_protection: 0
    feather_falling: 0
    thorns: 0

wind-charge:
  enabled: false
  seconds: 30
```

### Limits and storage

`allowed-maces` controls registered maces across the server, including offline owners. `-1`, the default, disables the cap and illegal-ID enforcement; storage rules still apply. `0` blocks new tracked crafts. A positive number caps tracked maces and removes maces that have no id. Lowering a positive limit does not delete existing registered maces. Once that cap is reached, further crafting is blocked until a tracked mace is destroyed or removed, or the limit is raised.

Storage rules apply even with `allowed-maces: -1`. These are the defaults:

```yaml
mace-storage:
  ender-chests: true
  shelves: true
  item-frames: true
  glow-item-frames: true
  chests: false
  barrels: false
  shulker-boxes: false
  bundles: false
  armor-stands: false
  hoppers: false
  droppers: false
  dispensers: false
  decorated-pots: false
  crafters: false
```

Players can carry maces and use grindstones. Anvils and enchanting tables follow
`allow-mace-enchanting`. Unsupported storage types are blocked. Shelves work only
on server versions that provide them; the plugin still compiles against 1.21.4.
Chest/hopper rules also cover vehicles with those inventory types. The crafter
option controls its inventory slots; automated creation of tracked maces remains
blocked while a cap is enabled.

Clicks, shift-clicks, drags, number-key/offhand swaps, direct shelf/display
interactions, and automated transfers use the destination's rule. Extraction from
blocked storage remains possible. Portable contents must be allowed in every
outer destination as well as in the bundle/shulker itself. Cleanup returns maces
from forbidden storage to a viewer when possible, otherwise drops them nearby.
Run `/maces reload` after editing these settings; loaded storage is queued for
reconciliation and online players are checked again.

Shelf support uses optional inventory names and standard block-data properties,
so the plugin keeps its 1.21.4 API target without linking newer shelf classes.
Direct front-face interactions check the main hand; powered shelves check the
three, six, or nine hotbar slots selected by their connection state. Unknown
connection data or an unloaded neighbour conservatively checks all nine slots.
Connected shelves across loaded chunk boundaries are queued after an interaction;
these checks never load neighbouring chunks. Shelf interactions still need live
validation on the server implementation being deployed.

Full audits inspect storage in server-thread scan units. Shared inventories and
double chests are recognized as the same physical slots, while distinct copies
of a tracked ID are removed. Allowed bundle/shulker contents participate in the
same checks, including portable storage on the ground. Each registered identity
uses one cap slot regardless of location, including after a database reload.
Illegally stacked tracked maces or portable containers still containing them are
reduced to one copy while a cap is enabled. Empty containers and administrator
maces retain their existing exemptions. Offline personal storage and unloaded
world/chunk references remain registered without loading them. Menus created by other plugins
with no physical location cannot be verified after they close; their records
remain reserved until rediscovered or removed administratively.

If an audit cannot find a mace, a final same-tick pass confirms absence before
invalidating its record, protecting items moved by plugins without events. This
rare verification, and capturing storage on chunk unload, may exceed the normal
cooperative scan budget. The reported full-audit timing measures elapsed time
across scheduled ticks; performance has not been benchmarked on a live server.
Loss events share one next-tick entity check, and portable contents are traversed
once per event to collect identities. Missing-item verification stops after its
player pass if every candidate is found; the regular full audit remains unchanged.

Administrator-issued untracked maces bypass the count and tracked-ID enforcement;
they still follow storage restrictions and cooldown rules.

### Cooldown calculation

A successful main-hand mace attack starts a cooldown calculated as:

```text
base seconds
+ sum of (mace enchantment level × configured seconds per level)
+ sum of (enchantment level × configured seconds per level) on each armor piece
```

For example, with the cooldown enabled, a base of `120`, a `density` rate of `5`, and Density III produce a 135-second cooldown before armor modifiers are applied. The default base is `0` and the cooldown is disabled, which leaves the vanilla attack-speed bar unchanged.

Run `/maces setcooldown 120` to set the base and enable cooldowns. Setting the base to `0` keeps enchantment modifiers active if cooldowns are enabled; set `cooldown.enabled: false` and run `/maces reload` to disable them entirely. Operators bypass mace cooldowns by default (see permissions below).

- Positive rates increase the duration; negative rates reduce it.
- A rate of `0` ignores that enchantment.
- A total of zero or less starts no cooldown, even if the configured minimum is
  positive. Positive totals are clamped to the configured minimum and maximum.
- With `ignore-thorns: true` (the default), Thorns still deals its reflected damage. That reflection does not start or refresh the mace cooldown, and an active cooldown does not cancel it. `armor-enchantments.thorns` only changes the swing timer; leave it at `0` so wearing Thorns does not add time.
- Other weapons remain usable during a mace cooldown.
- Cooldowns survive reconnecting while the plugin remains running. They are held
  in memory and do not persist across server restarts.

Wind charges use their own fixed duration and do not use enchantment modifiers.
The mace cooldown bypass permission does not bypass the wind-charge cooldown.

### Messages and performance

Set `broadcasts.crafted` to `false` to turn off server-wide crafting announcements.
It defaults to `true`, including for existing configurations without this setting.
Edit `messages.crafted-broadcast` to change the announcement text or remove
`%player%` to omit the crafter's name. For example, add or update these keys in your
existing configuration, then run `/maces reload`:

```yaml
broadcasts:
  crafted: false

messages:
  crafted-broadcast: "&6A mace has been crafted! &7(%current%/%max%)"
```

Edit the `messages` section to customize feedback. Messages support `&` color
codes and the placeholders shown in the defaults, such as `%player%`, `%seconds%`,
`%current%`, and `%max%`.

The `performance` section controls cooperative scan budgets, audit frequency,
optional background registry writes, optional Paper block-state queries, and timing
logs. A scan unit can exceed its time budget; the budget is not a hard tick-time
limit. See [OPTIMIZATION_REVIEW.md](OPTIMIZATION_REVIEW.md) for implementation
details and validation limits.

## Commands

| Command | Description |
| --- | --- |
| `/maces` | Show the tracked count, mace IDs, and holder information. |
| `/maces reload` | Reload configuration and refresh mace recipes. |
| `/maces setlimit <amount>` | Set the maximum number of tracked maces. |
| `/maces setcooldown <seconds>` | Set the base mace cooldown. |
| `/maces enchanting <on\|off>` | Toggle mace use in anvils and enchanting tables. |
| `/maces remove <id>` | Invalidate a tracked mace and queue removal from loaded inventories and entities. |
| `/maces giveuntracked` | Give yourself an administrator mace that does not count toward the limit; players only. |
| `/maces clearuntracked` | Queue a sweep to remove administrator-issued untracked maces from loaded inventories and entities. |

Numeric arguments must be nonnegative whole numbers. `remove` accepts a full UUID
or an unambiguous prefix, including the short ID shown by `/maces`.

Removal sweeps report completion after queued work finishes. They do not directly
edit offline player files or scan unloaded chunks. Administrative revocations persist
in SQLite: removed identities are deleted when encountered later, including nested
storage and when `allowed-maces: -1`. `clearuntracked` only removes
matching items encountered by its loaded-world sweep.

## Permissions

All declared permissions default to operators. With LuckPerms enabled, player
checks use Bukkit's `hasPermission` result, including explicit grants or denials.
Without LuckPerms, player access falls back to operator status. Non-player command
senders pass the permission check, although `giveuntracked` requires a player.

| Permission | Access |
| --- | --- |
| `macemanager.admin` | All commands below; does not include the cooldown bypass. |
| `macemanager.command.list` | `/maces` |
| `macemanager.command.reload` | `/maces reload` |
| `macemanager.command.setlimit` | `/maces setlimit` |
| `macemanager.command.setcooldown` | `/maces setcooldown` |
| `macemanager.command.enchanting` | `/maces enchanting` |
| `macemanager.command.remove` | `/maces remove` |
| `macemanager.command.giveuntracked` | `/maces giveuntracked` |
| `macemanager.command.clearuntracked` | `/maces clearuntracked` |
| `macemanager.bypass.cooldown` | Mace attacks neither start nor wait for a mace cooldown. |

Operators bypass mace cooldowns by default. With LuckPerms enabled, explicitly deny
`macemanager.bypass.cooldown` to an operator if they should follow the cooldown.

## Building and testing

Use the checked-in Gradle wrapper with a discoverable Java 21 JDK:

```powershell
# Windows
.\gradlew.bat test build
```

```sh
# Linux/macOS
./gradlew test build
```

The resulting JAR is written to `build/libs/MaceManager-1.0.1.jar`. Test reports are
written to `build/reports/tests/test/index.html`. The first build needs network
access to download Gradle and dependencies.

To compile and run the suite against the Paper 1.21.11 API:

```powershell
.\gradlew.bat test build '-PpaperApiVersion=1.21.11-R0.1-SNAPSHOT'
```

```sh
./gradlew test build -PpaperApiVersion=1.21.11-R0.1-SNAPSHOT
```

Omitting the property keeps the default Paper 1.21.4 target. Neither command starts
a Minecraft server.

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md) before submitting changes. Contributor
credits are recorded in [CONTRIBUTORS.md](CONTRIBUTORS.md).

For ordinary bug reports, include the plugin version, server implementation and
version, Java version, reproduction steps, and sanitized logs. Follow
[SECURITY.md](SECURITY.md) for private vulnerability reporting.

## License

MaceManager is licensed under **GPL-3.0-only**. See [LICENSE](LICENSE) and the
[contribution guide](CONTRIBUTING.md#license-and-credit) for terms.

The bundled [Xerial SQLite JDBC driver](https://github.com/xerial/sqlite-jdbc) is
distributed under Apache-2.0 with BSD-2-Clause notices for its inherited code.
The driver license files are retained under
`META-INF/maven/org.xerial/sqlite-jdbc/` in the built JAR.
