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
        return queuedChunks.isEmpty();
    }

    public void queueChunk(org.bukkit.Chunk chunk) {
        if (!plugin.settings().hasMaceLimit()) {
            return;
        }
        String key = chunk.getWorld().getUID() + ":" + chunk.getX() + ":" + chunk.getZ();
        if (!queuedChunks.add(key)) {
            return;
        }
        auditRequested = true;
        ScanQueue.Job job = plugin.scans().submit(() -> {
            queuedChunks.remove(key);
            auditRequested = true;
        });
        job.add(() -> cleanup.queueChunk(chunk, job));
    }

    public void markDirty(Player player) {
        dirtyPlayers.add(player.getUniqueId());
    }

    public void reconcileDirty() {
        long start = System.nanoTime();
        Set<UUID> seen = new HashSet<>();
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
        dirtyViewers(event.getView());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void inventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
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
        markDirty(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void playerRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        markDirty(event.getPlayer());
    }

    @EventHandler
    public void playerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        dirtyPlayers.remove(event.getPlayer().getUniqueId());
    }

    private void dirtyViewers(org.bukkit.inventory.InventoryView view) {
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

    public void reconcileAll() {
        if (!plugin.settings().hasMaceLimit()) {
            auditRequested = false;
            return;
        }
        long start = System.nanoTime();
        auditRequested = false;
        groundItems.clear();
        dirtyPlayers.clear();
        Set<UUID> seen = new HashSet<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            cleanup.clean(player, seen);
        }
        MaceItems items = plugin.maceItems();
        for (World world : plugin.getServer().getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                reconcileGroundItem(item, items, seen);
            }
        }
        for (MaceRecord record : plugin.registry().all()) {
            // A queued container may still contain this record's physical item.
            // Duplicate checks above always run; missing-item decisions wait for cleanup.
            if (!queuedChunks.isEmpty() || seen.contains(record.id())) {
                continue;
            }
            if (record.dropped()) {
                World world = plugin.getServer().getWorld(record.dropWorld());
                if (world == null || !world.isChunkLoaded(record.dropChunkX(), record.dropChunkZ())) {
                    continue;
                }
                plugin.destroyMace(record.id());
                continue;
            }
            if (record.holder() != null && plugin.getServer().getPlayer(record.holder()) != null) {
                plugin.destroyMace(record.id());
            }
        }
        maximumAuditNanos = Math.max(maximumAuditNanos, System.nanoTime() - start);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            cleanup.clean(player, new HashSet<>());
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
        MaceItems.Classification classification = plugin.maceItems().classify(stack);
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
        if (plugin.settings().hasMaceLimit() && !classification.untracked() && (id == null || !plugin.registry().contains(id))) {
            event.setCancelled(true);
            event.getItem().remove();
            Notices.send(player, plugin.settings().format("illegal-removed"));
            return;
        }
        if (id != null) {
            plugin.registry().hold(id, player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        markDirty(event.getPlayer());
        Item item = event.getItemDrop();
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
        UUID id = plugin.maceItems().trackedId(event.getEntity().getItemStack());
        if (id != null) {
            plugin.destroyMace(id);
        }
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

    private void reconcileGroundItem(Item item, MaceItems items, Set<UUID> seen) {
        ItemStack stack = item.getItemStack();
        MaceItems.Classification classification = items.classify(stack);
        UUID id = classification.id();
        if (classification.mace()) {
            groundItems.put(item.getUniqueId(), item);
        } else {
            groundItems.remove(item.getUniqueId());
        }
        boolean illegal = plugin.settings().hasMaceLimit()
                && classification.mace()
                && !classification.untracked()
                && (id == null || !plugin.registry().contains(id) || !seen.add(id));
        if (illegal) {
            item.remove();
            return;
        }
        if (id != null) {
            plugin.registry().markDropped(id, item.getLocation());
        }
    }

    private void rememberGround(Item item) {
        if (plugin.maceItems().isMace(item.getItemStack())) {
            groundItems.put(item.getUniqueId(), item);
        }
    }

    private void destroyIfGone(Item item) {
        UUID id = plugin.maceItems().trackedId(item.getItemStack());
        if (id == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!item.isValid() || item.isDead()) {
                plugin.destroyMace(id);
            }
        });
    }
}
