package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.text.Notices;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TrackingListener implements Listener {
    private final MaceManagerPlugin plugin;
    private final PlayerMaceCleanup cleanup;
    private final Set<UUID> dirtyPlayers = new HashSet<>();
    private final Map<UUID, Item> groundItems = new HashMap<>();
    private final Set<String> queuedChunks = new HashSet<>();
    private long maximumAuditNanos;
    private long maximumDirtyNanos;
    private boolean auditRequested = true;
    private boolean auditRunning;
    private long eventRevision;
    private final Map<UUID, Long> touched = new HashMap<>();
    private final Set<UUID> destructionCandidates = new HashSet<>();
    private final Map<Item, Set<UUID>> pendingDestructionChecks = new HashMap<>();
    private boolean destructionCheckScheduled;

    public TrackingListener(MaceManagerPlugin plugin) {
        this.plugin = plugin;
        this.cleanup = new PlayerMaceCleanup(plugin);
    }

    public void reconcileLoadedWorlds() {
        for (World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                queueChunk(chunk);
            }
        }
    }

    public boolean auditDue() {
        return auditRequested;
    }

    public boolean readyForAudit() {
        return queuedChunks.isEmpty() && !auditRunning;
    }

    public void queueChunk(org.bukkit.Chunk chunk) {
        String key = chunk.getWorld().getUID() + ":" + chunk.getX() + ":" + chunk.getZ();
        if (!queuedChunks.add(key)) {
            return;
        }
        touchChunk(chunk);
        auditRequested = true;
        ScanQueue.Job job = plugin.scans().submit(() -> {
            queuedChunks.remove(key);
            auditRequested = true;
        });
        MaceObservations seen = new MaceObservations();
        job.add(() -> cleanup.queueChunk(chunk, job, seen));
    }

    public void markDirty(Player player) {
        dirtyPlayers.add(player.getUniqueId());
    }

    public void reconcileDirty() {
        long start = System.nanoTime();
        MaceObservations seen = new MaceObservations();
        Set<UUID> pending = new HashSet<>(dirtyPlayers);
        dirtyPlayers.clear();
        for (UUID id : pending) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                cleanup.clean(player, seen);
            }
        }
        // Known ground maces are cheap to revisit and can move without inventory events.
        for (Item item : java.util.List.copyOf(groundItems.values())) {
            if (!item.isValid() || item.isDead()) {
                groundItems.remove(item.getUniqueId());
            } else {
                reconcileGroundItem(item, plugin.maceItems(), seen);
            }
        }
        maximumDirtyNanos = Math.max(maximumDirtyNanos, System.nanoTime() - start);
        // Missing ownership is decided only by the complete audit, never a partial pass.
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void inventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        touch(event.getCursor());
        touch(event.getCurrentItem());
        if (event.getWhoClicked() instanceof Player player) {
            int hotbar = event.getHotbarButton();
            if (hotbar >= 0) {
                touch(player.getInventory().getItem(hotbar));
            }
            if (event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) {
                touch(player.getInventory().getItemInOffHand());
            }
        }
        dirtyViewers(event.getView());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void inventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        touch(event.getOldCursor());
        dirtyViewers(event.getView());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void inventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        dirtyViewers(event.getView());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void inventoryOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        dirtyViewers(event.getView());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void playerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        for (ItemStack stack : event.getDrops()) {
            touch(stack);
        }
        markDirty(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void playerRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        markDirty(event.getPlayer());
    }

    @EventHandler
    public void playerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        touchPlayer(event.getPlayer());
        cleanup.clean(event.getPlayer(), new MaceObservations());
        dirtyPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void dirtyViewers(org.bukkit.inventory.InventoryView view) {
        queueInventory(view.getTopInventory());
        if (view.getPlayer() instanceof Player player) {
            markDirty(player);
        }
        for (org.bukkit.entity.HumanEntity viewer : view.getTopInventory().getViewers()) {
            if (viewer instanceof Player player) {
                markDirty(player);
            }
        }
    }

    public long maximumAuditNanos() {
        return maximumAuditNanos;
    }

    public long maximumDirtyNanos() {
        return maximumDirtyNanos;
    }

    /** Full storage audits are cooperative server-thread jobs, not whole-world tick scans. */
    public void reconcileAll() {
        if (auditRunning) {
            return;
        }
        auditRunning = true;
        auditRequested = false;
        long revision = eventRevision;
        long start = System.nanoTime();
        touched.keySet().removeIf(id -> !plugin.registry().contains(id));
        MaceObservations seen = new MaceObservations();
        ScanQueue.Job job = plugin.scans().submit(() -> {
            try {
                if (queuedChunks.isEmpty()) {
                    var missing = plugin.registry().all().stream()
                            .filter(record -> (plugin.settings().hasMaceLimit() || destructionCandidates.contains(record.id()))
                                    && touched.getOrDefault(record.id(), 0L) <= revision
                                    && !seen.contains(record.id()) && locationLoaded(record.location(), record.holder()))
                            .toList();
                    if (!missing.isEmpty()) {
                        confirmMissing(missing, revision);
                    }
                    destructionCandidates.removeIf(id -> !plugin.registry().contains(id) || seen.contains(id));
                }
            } catch (RuntimeException exception) {
                auditRequested = true;
                plugin.getLogger().warning("Mace audit verification failed; missing-item decisions deferred: " + exception);
            } finally {
                auditRunning = false;
                maximumAuditNanos = Math.max(maximumAuditNanos, System.nanoTime() - start);
            }
        }, exception -> {
            auditRunning = false;
            auditRequested = true;
            plugin.getLogger().warning("Full mace audit incomplete; missing-item decisions deferred: " + exception);
        });
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            job.add(() -> {
                if (player.isOnline()) {
                    cleanup.clean(player, seen);
                }
            });
        }
        for (World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                job.add(() -> cleanup.queueChunk(chunk, job, seen));
            }
            job.add(() -> {
                for (Item item : world.getEntitiesByClass(Item.class)) {
                    job.add(() -> {
                        if (item.isValid() && !item.isDead()) {
                            reconcileGroundItem(item, plugin.maceItems(), seen);
                        }
                    });
                }
            });
        }
    }

    private void confirmMissing(java.util.List<MaceRecord> candidates, long revision) {
        // Plugins/commands can move items without events. Only the rare missing-record path
        // needs a final same-tick pass; a multi-tick negative result alone is not proof of loss.
        // This verification may exceed the cooperative scan budget.
        MaceObservations confirmed = new MaceObservations();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            cleanup.clean(player, confirmed);
        }
        // The full audit already covered storage and duplicates. This extra pass only
        // proves absence; finding every candidate in live player slots settles it.
        if (candidates.stream().allMatch(record -> confirmed.contains(record.id()))) {
            return;
        }
        for (World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                cleanup.scanChunkNow(chunk, confirmed);
            }
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (item.isValid() && !item.isDead()) {
                    reconcileGroundItem(item, plugin.maceItems(), confirmed);
                }
            }
        }
        for (MaceRecord record : candidates) {
            if (touched.getOrDefault(record.id(), 0L) <= revision && !confirmed.contains(record.id())
                    && locationLoaded(record.location(), record.holder())) {
                plugin.destroyMace(record.id());
            }
        }
    }

    private boolean locationLoaded(MaceLocation location, UUID holder) {
        if (location.state() == MaceLocation.State.CARRIED) {
            return holder != null && plugin.getServer().getPlayer(holder) != null;
        }
        if (location.inventoryOwner() != null) {
            // Plugin menus without a physical location cannot be inspected after they close.
            return !location.storageType().startsWith("OPEN_")
                    && plugin.getServer().getPlayer(location.inventoryOwner()) != null;
        }
        World world = plugin.getServer().getWorld(location.world());
        return world != null && world.isChunkLoaded(location.chunkX(), location.chunkZ());
    }

    public void touch(ItemStack stack) {
        if (stack == null) {
            return;
        }
        UUID id = plugin.maceItems().trackedId(stack);
        if (id != null && plugin.registry().contains(id)) {
            touched.put(id, ++eventRevision);
        }
        for (ItemStack child : NestedMaces.contents(stack)) {
            touch(child);
        }
    }

    private void touchPlayer(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            touch(stack);
        }
        for (ItemStack stack : player.getEnderChest().getContents()) {
            touch(stack);
        }
        touch(player.getItemOnCursor());
    }

    private void touchChunk(org.bukkit.Chunk chunk) {
        long revision = ++eventRevision;
        for (MaceRecord record : plugin.registry().all()) {
            MaceLocation location = record.location();
            if (chunk.getWorld().getName().equals(location.world())
                    && location.chunkX() == chunk.getX() && location.chunkZ() == chunk.getZ()) {
                touched.put(record.id(), revision);
            }
        }
    }

    public void queueInventory(org.bukkit.inventory.Inventory inventory) {
        if (inventory == null) {
            return;
        }
        if (inventory instanceof org.bukkit.inventory.DoubleChestInventory combined) {
            queueInventory(combined.getLeftSide());
            queueInventory(combined.getRightSide());
        } else if (inventory.getHolder() instanceof org.bukkit.entity.Entity entity) {
            queueLocation(entity.getLocation());
        } else {
            queueLocation(inventory.getLocation());
        }
    }

    public void queueLocation(org.bukkit.Location location) {
        if (location != null && location.getWorld() != null
                && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            queueChunk(location.getWorld().getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4));
        }
    }

    public void beforeUnload(org.bukkit.Chunk chunk) {
        touchChunk(chunk);
        cleanup.scanChunkNow(chunk, new MaceObservations());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entitiesUnload(org.bukkit.event.world.EntitiesUnloadEvent event) {
        touchChunk(event.getChunk());
        MaceObservations seen = new MaceObservations();
        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            if (entity instanceof Item item) {
                reconcileGroundItem(item, plugin.maceItems(), seen);
            } else {
                cleanup.entity(entity, seen);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            cleanup.clean(player, new MaceObservations());
            markDirty(player);
            auditRequested = true;
            plugin.cooldowns().reapply(player);
        });
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            markDirty(player);
        }
        ItemStack stack = event.getItem().getItemStack();
        touch(stack);
        MaceItems.Classification classification = plugin.maceItems().classify(stack);
        if (!(event.getEntity() instanceof Player) && plugin.settings().hasMaceLimit() && NestedMaces.contains(stack)) {
            event.setCancelled(true);
            return;
        }
        if (!classification.mace()) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            if (plugin.settings().hasMaceLimit()) {
                event.setCancelled(true);
            }
            return;
        }
        UUID id = classification.id();
        if (plugin.registry().isRevoked(id) || (plugin.settings().hasMaceLimit()
                && !classification.untracked() && (id == null || !plugin.registry().contains(id)))) {
            event.setCancelled(true);
            event.getItem().remove();
            Notices.send(player, plugin.settings().format("illegal-removed"));
            return;
        }
        // Actual possession is recorded from the resulting inventory, after all plugins can cancel.
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        markDirty(event.getPlayer());
        Item item = event.getItemDrop();
        touch(item.getItemStack());
        rememberGround(item);
        MaceItems.Classification classification = plugin.maceItems().classify(item.getItemStack());
        UUID id = classification.id();
        if (id != null && plugin.registry().contains(id)) {
            plugin.registry().markDropped(id, item.getLocation());
            return;
        }
        if (plugin.settings().hasMaceLimit() && classification.mace() && !classification.untracked()) {
            item.remove();
            Notices.send(event.getPlayer(), plugin.settings().format("illegal-removed"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        touch(item.getItemStack());
        rememberGround(item);
        ItemStack stack = item.getItemStack();
        MaceItems.Classification classification = plugin.maceItems().classify(stack);
        UUID id = classification.id();
        if (id != null && plugin.registry().contains(id)) {
            plugin.registry().markDropped(id, item.getLocation());
            return;
        }
        if (plugin.settings().hasMaceLimit() && classification.mace() && !classification.untracked()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (item.isValid()) {
                    item.remove();
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDespawn(ItemDespawnEvent event) {
        destroyIfGone(event.getEntity());
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item) {
            destroyIfGone(item);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemBurn(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item) {
            destroyIfGone(item);
        }
    }

    private void reconcileGroundItem(Item item, MaceItems items, MaceObservations seen) {
        ItemStack stack = item.getItemStack();
        MaceItems.Classification classification = items.classify(stack);
        UUID id = classification.id();
        if (classification.mace()) {
            groundItems.put(item.getUniqueId(), item);
        } else if (NestedMaces.contains(stack)) {
            groundItems.put(item.getUniqueId(), item);
            cleanup.ground(item, seen);
        } else {
            groundItems.remove(item.getUniqueId());
        }
        boolean unique = id == null || !plugin.registry().contains(id)
                || seen.claim(id, item.getUniqueId(), () -> item.isValid() && !item.isDead()
                        && id.equals(items.trackedId(item.getItemStack())));
        boolean illegal = plugin.registry().isRevoked(id) || (plugin.settings().hasMaceLimit()
                && classification.mace()
                && !classification.untracked()
                && (id == null || !plugin.registry().contains(id) || !unique));
        if (illegal) {
            item.remove();
            return;
        }
        if (plugin.settings().hasMaceLimit() && classification.mace() && !classification.untracked()
                && stack.getAmount() > 1) {
            stack.setAmount(1);
            item.setItemStack(stack);
        }
        if (id != null) {
            plugin.registry().markDropped(id, item.getLocation());
        }
    }

    private void rememberGround(Item item) {
        if (NestedMaces.contains(item.getItemStack())) {
            groundItems.put(item.getUniqueId(), item);
        }
    }

    private void destroyIfGone(Item item) {
        ItemStack stack = item.getItemStack();
        if (stack == null || (stack.getType() != org.bukkit.Material.MACE && NestedMaces.destination(stack) == null)) {
            return;
        }
        Set<UUID> candidates = new HashSet<>();
        // Collect in one traversal instead of first walking the same portable contents
        // with contains(). Preserve IDs from every event if the item's contents change.
        collectIds(stack, candidates);
        if (candidates.isEmpty()) {
            return;
        }
        pendingDestructionChecks.computeIfAbsent(item, ignored -> new HashSet<>()).addAll(candidates);
        if (destructionCheckScheduled) {
            return;
        }
        destructionCheckScheduled = true;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            var pending = new HashMap<>(pendingDestructionChecks);
            pendingDestructionChecks.clear();
            destructionCheckScheduled = false;
            for (var entry : pending.entrySet()) {
                Item checked = entry.getKey();
                if (!checked.isValid() || checked.isDead()) {
                    // Pickups and duplicate despawns need the same full-audit verification.
                    // Failed audits retain candidates for retry, including with the cap off.
                    auditRequested = true;
                    destructionCandidates.addAll(entry.getValue());
                }
            }
        });
    }

    private void collectIds(ItemStack stack, Set<UUID> ids) {
        UUID id = plugin.maceItems().trackedId(stack);
        if (id != null) {
            ids.add(id);
        }
        for (ItemStack child : NestedMaces.contents(stack)) {
            collectIds(child, ids);
        }
    }
}
