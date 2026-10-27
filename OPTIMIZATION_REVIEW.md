# Optimization review

Reviewed all Java sources, configuration, build settings, and existing tests on 2026-10-27.
The changes below reduce identifiable work in the code. They are not measured TPS or latency claims; no Minecraft server was available for profiling.

## Implemented

| Area | Change | Effect |
| --- | --- | --- |
| Registry persistence | Coalesce mutations into one scheduled flush on the next server tick; skip clean saves. | A burst of crafts, ownership changes, or removals no longer serializes and writes the complete registry once per mutation. Memory updates remain immediate. |
| Save integrity | Write a temporary file and replace the registry atomically where supported, with a replacement fallback. Retain dirty state after failure. | Avoids truncating the existing registry during serialization/write failures; allows retrying a failed save. |
| Drop tracking | Derive chunk coordinates from block coordinates instead of calling `Location.getChunk()`. | Tracking does not resolve or load a chunk just to obtain its coordinates. Negative coordinates are covered by a regression test. |
| Periodic inventory cleanup | Return either the shulker or bundle extraction result directly. Reuse cleanup services. Remove intermediate frame/armor-stand lists and the extra ground-item list copy. | Eliminates temporary collections and service objects from frequently repeated scans. |
| Cleanup correctness | Persist changed nested contents back into their inventory/cursor slots. Return extracted player-inventory maces after scanning its slots. | Supports item snapshots and prevents a returned mace from being scanned again and deleted as a duplicate. |
| Administrative sweep | Handle dropped items during the existing chunk entity traversal. Remove only matching nested maces. Delete the obsolete bundle-restoration helper. | Avoids a separate world entity query and extraction/reinsertion of unrelated maces; preserves surviving shulker slots and bundle order. Also handles maces inside dropped bundles/shulkers. |
| Item metadata | Read the untracked flag and tracked ID from one metadata snapshot in `trackedId`. | Avoids a second metadata fetch/copy for tracked mace lookups. |
| Hit cooldowns | Accumulate enchantment contributions directly instead of building and merging intermediate maps for the mace and armor. Use long arithmetic for contributions. | Reduces per-hit allocations and prevents integer overflow from producing incorrect cooldowns. |
| Cooldown lifecycle | Use elapsed monotonic time and prune expired entries in the existing periodic task. | Clock corrections do not affect cooldown duration, and inactive players' expired entries are reclaimed. |
| Action bars | Resolve reflective classes, methods, and enum values once. | Repeated action-bar messages avoid repeated reflective discovery. Unsupported implementations still fall back to chat. |
| Event handling | Skip deferred join cleanup if the player disconnected; ignore cancelled despawns at monitor priority. | Avoids stale-player work and forgetting a mace after another plugin cancels its despawn. |
| ID matching | Remove redundant lowercase conversion of UUID strings. | Avoids unnecessary string processing. |

## Follow-up implementation

The seven follow-ups now have implementation paths. There is still no live Minecraft
server in this workspace, so no before/after worst-case tick-time or TPS claim is
made. The full player/ground audit deliberately remains a single server-thread
pass: splitting its `seen` set across ticks without revalidating moving items can
misidentify transfers as duplicates or destroy records for items not yet visited.

| Area | Implemented behavior |
| --- | --- |
| Budgeted cleanup | Startup and chunk-load container/equipment cleanup share a cooperative queue with administrative sweeps. Each chunk query, container inventory, entity, or player inventory is a work unit. The defaults allow at most 32 units and approximately 2 ms per tick. |
| Event-driven reconciliation | Click, drag, open, close, join, respawn, death, drop, and pickup activity marks affected players dirty. Every 20 ticks, dirty players and known ground maces are checked with a shared duplicate set. Full audits still discover changes from commands and other plugins and enforce duplicates against players outside the dirty set. |
| Ordered background persistence | Optional `performance.async-registry-writes` captures immutable UUID/string/primitive snapshots on the server thread. One writer serializes and atomically replaces the file. Only one write is in flight; later mutations coalesce in memory until it finishes. Revision acknowledgements preserve dirty state after failures. Shutdown drains the old snapshot and writes/drains the latest state before stopping the writer. |
| Zero-rate enchantments | Config load retains all keys for unknown-enchantment warnings and caches immutable nonzero-rate maps. With the default zero rates, cooldown calculation does not fetch mace enchantments or armor contents. |
| Recipe synchronization | Destruction/removal bursts request one sync on the next tick. Crafting already synchronizes once after its bulk operation. Explicit setting changes/reload remain immediate; a full audit also resynchronizes captured recipes. |
| Item classification | Cleanup and tracking paths use one short-lived classification carrying mace status, the untracked flag, and tracked ID. No mutable metadata is cached across events. |
| Paper queries | Optional `performance.paper-direct-states` discovers snapshot-free chunk/block state methods reflectively. Missing/failed optional methods fall back to Bukkit snapshot APIs without linking Paper-only methods. Enable only after server validation; see the [Paper 1.21.4 Chunk API](https://jd.papermc.io/paper/1.21.4/org/bukkit/Chunk.html). |

### Timing and tuning

`performance.log-timings: true` logs observed maximum scan-tick, scan-unit,
full-audit, dirty-pass, cooldown-calculation, and registry-write times on disable.
These measure plugin work, not total server tick duration. Registry time includes
serialization and replacement; it excludes server-thread snapshot capture. A
cooperative budget cannot interrupt one expensive inventory or chunk query. Loaded
chunk enumeration and query result allocation also remain synchronous; extremely
large individual chunks can still exceed the budget.

The fallback audit defaults to `performance.full-audit-ticks: 1200` (60 seconds at
20 TPS), with a minimum of 100 ticks. Join/chunk activity requests an earlier audit
once queued chunk cleanup finishes, at most once per 100 ticks. Full duplicate
audits continue at the configured interval during sustained chunk loading;
missing-item record deletion waits until pending container cleanup is finished.
Offline holders and unloaded dropped locations continue to retain their records.

To establish a baseline, profile the prior build under the same loaded-world,
container, ground-item, player, and registry workload as this build. Capture server
tick maxima as well as plugin timings. Test startup, a burst of chunk loads, and
both removal commands. Enable the background writer only if measured synchronous
write latency warrants it; it defaults off and requires restart. Compare Paper
queries separately with `paper-direct-states` off/on. Confirm Bukkit/Spigot fallback
behavior on those actual servers. No server JAR or live test server was available
for these measurements here.

### Removal command completion

`/maces remove <id>` invalidates the registry ID immediately, then queues physical
removal. `/maces clearuntracked` queues physical removal without changing the
registry. Both announce that work was queued and send their existing success
message only after every unit in that sweep has finished. Failed units are logged,
the rest of the sweep continues, and the sender receives a failure message instead
of success. Plugin shutdown cancels unfinished sweeps without claiming completion.

Sweeps cover players online and chunks loaded when the command is issued, resolving
current contents when each unit runs. They skip players who disconnect and chunks
that unload, and never load chunks or edit offline player files. Items transferred
to an already-scanned location during a sweep may escape that physical sweep;
invalid tracked IDs are removed by subsequent normal reconciliation. An untracked
sweep may need to be repeated after transfers, reconnects, or chunk loads. The
reported untracked count is the number of matching stacks physically removed by
that sweep, excluding removals made concurrently by normal enforcement.

### Recipe ownership policy

Startup captures keyed mace recipes. Reload adopts currently present mace recipes,
including replacements under existing keys, and retains recipes previously
suppressed by MaceManager. MaceManager owns restoration/suppression of this captured
set: if another plugin removes one while crafting is allowed, the next full audit
or explicit sync restores it. New keys added by other plugins are adopted on
reload. Crafting's registry-count check remains authoritative even before recipe
synchronization. There is no recipe-state cache that silently assumes other plugins
have left the registry unchanged.

Registry writes remain batched until the next server tick in either mode. A process
crash can lose changes not yet flushed (including an in-flight background write).
Atomic replacement protects file replacement where supported; it is not a guarantee
against power loss. Failed writes retry during periodic maintenance, further
mutations, and shutdown; persistent filesystem failures remain logged failures.

## Related reliability findings left for separate changes

- `ConfigStore.replace` matches a textual key prefix without checking the colon or full YAML path. A similarly named setting can be replaced accidentally; duplicate leaf names are ambiguous, and inline comments are discarded.
- Crafting's `emptySlots` counts the offhand, while `Inventory.addItem` normally targets storage slots. Verify shift crafting with full storage and an empty offhand. Also test custom multi-result recipes and inventory click variants.
- Shulker/bundle extraction inspects direct mace contents only. Supporting nested containers requires recursive handling with a depth/size budget and a policy for malformed item data.
- Add a server-backed integration suite for crafting, cancelled pickup/drop/spawn events, nested-container cleanup, shared inventories, and interaction with other plugins. Unit tests cannot verify actual Bukkit item-copy semantics or event ordering.

## Validation

Gradle regression tests cover registry write coalescing, clean-save suppression,
failure/retry, immutable background snapshots, ordered shutdown flushing, ID
matching, drop coordinates, cooldown expiry/reapplication and arithmetic boundaries,
active-rate filtering, cooperative queue budgets, dynamically discovered work,
completion/failure callbacks, queue cancellation, and optional Paper-query fallback. Validation uses Java 21:

```powershell
.\gradlew.bat test build
```

Live-server smoke testing, worst-case baseline profiling, Paper/Spigot compatibility
checks, and performance comparisons remain outstanding. Existing Gradle/API
deprecation warnings remain.
