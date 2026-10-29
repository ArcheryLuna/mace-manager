package com.macemanager.limit;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageLifecycleTest {
    @TempDir Path directory;
    private final StorageTestSupport f = new StorageTestSupport();

    @Test
    void despawningDuplicateDoesNotDestroyStoredOriginal() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var player = f.player("Viewer");
        ItemStack original = f.mace(id, owner);
        player.ender().slots()[0] = original;
        Item duplicate = drop(f.mace(id, owner));
        f.tracking.onDespawn(new ItemDespawnEvent(duplicate, duplicate.getLocation()));
        when(duplicate.isValid()).thenReturn(false);
        f.nextTick();
        audit();
        assertTrue(f.records.containsKey(id));
        assertSame(original, player.ender().slots()[0]);
        assertEquals(owner, f.records.get(id).holder());
        assertEquals("ENDER_CHEST", f.records.get(id).location().storageType());
    }

    @Test
    void actualDestructionReleasesRecordEvenWithCapDisabled() {
        when(f.settings.hasMaceLimit()).thenReturn(false);
        UUID id = UUID.randomUUID();
        Item item = drop(f.mace(id, UUID.randomUUID()));
        f.tracking.onDespawn(new ItemDespawnEvent(item, item.getLocation()));
        assertTrue(f.records.containsKey(id)); // Wait for the server's actual outcome.
        when(item.isValid()).thenReturn(false);
        f.nextTick();
        audit();
        assertFalse(f.records.containsKey(id));
    }

    @Test
    void lateDespawnCancellationPreservesRecord() {
        UUID id = UUID.randomUUID();
        Item item = drop(f.mace(id, UUID.randomUUID()));
        var event = new ItemDespawnEvent(item, item.getLocation());
        f.tracking.onDespawn(event);
        event.setCancelled(true); // Entity stays alive when another plugin cancels.
        f.nextTick();
        assertTrue(f.records.containsKey(id));
        verify(f.plugin, never()).destroyMace(id);
    }

    @Test
    void failedDestructionAuditRetainsCandidateForRetryWithCapDisabled() {
        when(f.settings.hasMaceLimit()).thenReturn(false);
        UUID id = UUID.randomUUID();
        Item item = drop(f.mace(id, UUID.randomUUID()));
        var chunk = f.chunk(0, 0, new BlockState[0]);
        when(chunk.getTileEntities()).thenThrow(new IllegalStateException("Unavailable storage"));
        f.tracking.onDespawn(new ItemDespawnEvent(item, item.getLocation()));
        when(item.isValid()).thenReturn(false);
        f.nextTick();
        audit();
        assertTrue(f.records.containsKey(id));
        assertTrue(f.tracking.auditDue());
        doReturn(new BlockState[0]).when(chunk).getTileEntities();
        audit();
        assertFalse(f.records.containsKey(id));
    }

    @Test
    void brokenFrameDropsSameIdentityAndOnlyActualPickupTransfersOwnership() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack mace = f.mace(id, owner);
        var player = f.player("Collector");
        ItemFrame frame = mock(ItemFrame.class);
        when(frame.getType()).thenReturn(EntityType.ITEM_FRAME);
        when(frame.getUniqueId()).thenReturn(UUID.randomUUID());
        when(frame.getLocation()).thenReturn(f.at(1, 64, 1));
        when(frame.isValid()).thenReturn(true);
        when(frame.getItem()).thenReturn(mace);
        f.chunk(0, 0, new BlockState[0], frame);
        audit();
        assertEquals(MaceLocation.State.DISPLAYED, f.records.get(id).location().state());
        when(frame.isValid()).thenReturn(false);
        Item item = drop(mace);
        audit();
        assertEquals(owner, f.records.get(id).holder());
        assertTrue(f.records.get(id).dropped());
        when(item.isValid()).thenReturn(false);
        player.inventory().slots()[0] = mace;
        audit();
        assertEquals(player.player().getUniqueId(), f.records.get(id).holder());
        assertEquals(1, f.records.size());
    }

    @Test
    void shulkerPlacementBreakDropAndRetrievalKeepNestedIdentity() {
        f.allowed.add("shulker-boxes");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack mace = f.mace(id, owner);
        var player = f.player("Carrier");
        var portable = shulker(mace);
        player.inventory().slots()[0] = portable.item();
        audit();
        assertEquals(owner, f.records.get(id).holder());
        assertEquals(player.player().getUniqueId(), f.records.get(id).location().inventoryOwner());
        player.inventory().slots()[0] = null;
        var placed = f.inventory(InventoryType.SHULKER_BOX, 27, f.at(1, 64, 1));
        placed.slots()[0] = mace;
        var chunk = f.chunk(0, 0, new BlockState[]{f.blockInventory(placed, 1, 64, 1)});
        audit();
        assertEquals(1, f.records.get(id).location().blockX());
        when(chunk.getTileEntities()).thenReturn(new BlockState[0]);
        f.blocks.clear();
        Item droppedBox = drop(portable.item());
        audit();
        assertEquals(droppedBox.getUniqueId(), f.records.get(id).location().entity());
        assertEquals(owner, f.records.get(id).holder());
        when(droppedBox.isValid()).thenReturn(false);
        player.inventory().slots()[0] = portable.item();
        audit();
        assertEquals(owner, f.records.get(id).holder());
        portable.inside().slots()[0] = null;
        player.inventory().slots()[1] = mace;
        audit();
        assertEquals(player.player().getUniqueId(), f.records.get(id).holder());
        assertEquals(1, f.records.size());
    }

    @Test
    void ruleReloadAppliesWhenUnloadedStorageReturnsWithoutLosingOwner() {
        f.allowed.add("chests");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(32, 64, 1));
        chest.slots()[0] = f.mace(id, owner);
        var chunk = f.chunk(2, 0, new BlockState[]{f.blockInventory(chest, 32, 64, 1)});
        f.tracking.beforeUnload(chunk);
        when(chunk.isLoaded()).thenReturn(false);
        when(f.world.isChunkLoaded(2, 0)).thenReturn(false);
        f.chunks.clear();
        f.allowed.remove("chests");
        when(f.settings.hasMaceLimit()).thenReturn(false);
        f.tracking.reconcileLoadedWorlds();
        audit();
        assertNotNull(chest.slots()[0]);
        when(chunk.isLoaded()).thenReturn(true);
        when(f.world.isChunkLoaded(2, 0)).thenReturn(true);
        f.chunks.add(chunk);
        f.tracking.queueChunk(chunk);
        f.drain();
        assertNull(chest.slots()[0]);
        assertEquals(1, f.ground.size());
        assertEquals(owner, f.records.get(id).holder());
        assertTrue(f.records.get(id).dropped());
    }

    @Test
    void cancelledRetrievalKeepsStoredLocationAfterDeferredAudit() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var viewer = f.player("Viewer");
        ItemStack mace = f.mace(id, owner);
        viewer.ender().slots()[0] = mace;
        when(viewer.view().getTopInventory()).thenReturn(viewer.ender().inventory());
        var event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(viewer.view());
        when(event.getWhoClicked()).thenReturn(viewer.player());
        when(event.getCurrentItem()).thenReturn(mace);
        when(event.getHotbarButton()).thenReturn(-1);
        when(event.isCancelled()).thenReturn(true);
        f.tracking.inventoryClick(event);
        f.tracking.reconcileDirty();
        audit();
        assertEquals(owner, f.records.get(id).holder());
        assertEquals("ENDER_CHEST", f.records.get(id).location().storageType());
        assertSame(mace, viewer.ender().slots()[0]);
    }

    @Test
    void adminRemovalSurvivesRestartAndCleansOfflineAndUnloadedNestedCopiesWithCapOff() {
        var player = f.player("Offline");
        UUID id = UUID.randomUUID();
        ItemStack mace = f.mace(id, player.player().getUniqueId());
        player.ender().slots()[0] = mace;
        var portable = shulker(mace);
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(32, 64, 1));
        chest.slots()[0] = portable.item();
        var chunk = f.chunk(2, 0, new BlockState[]{f.blockInventory(chest, 32, 64, 1)});
        when(player.player().isOnline()).thenReturn(false);
        when(chunk.isLoaded()).thenReturn(false);
        f.chunks.clear();
        var file = directory.resolve("maces.db").toFile();
        MaceRegistry registry = new MaceRegistry(file, Logger.getLogger("test"), f.tasks::add, true);
        registry.load();
        registry.register(id, player.player());
        registry.save();
        registry.revoke(id); // Same registry operation used by /maces remove.
        new MaceSweeper(f.plugin).remove(stack -> id.equals(f.items.trackedId(stack)), count -> assertEquals(0, count),
                () -> fail("Removal sweep failed"));
        f.drain();
        registry.close(); // Flush a revocation queued behind the older active-record snapshot.
        MaceRegistry reloaded = new MaceRegistry(file, Logger.getLogger("test"), f.tasks::add);
        try {
            reloaded.load();
            assertEquals(0, reloaded.count());
            assertTrue(reloaded.isRevoked(id));
            when(f.plugin.registry()).thenReturn(reloaded);
            when(f.settings.hasMaceLimit()).thenReturn(false);
            ItemStack unrelated = f.mace(UUID.randomUUID(), player.player().getUniqueId());
            player.inventory().slots()[0] = unrelated; // Unknown IDs remain allowed with the cap off.
            f.allowed.addAll(List.of("chests", "shulker-boxes"));
            when(player.player().isOnline()).thenReturn(true);
            when(chunk.isLoaded()).thenReturn(true);
            f.chunks.add(chunk);
            audit();
            assertNull(player.ender().slots()[0]);
            assertSame(portable.item(), chest.slots()[0]);
            assertNull(portable.inside().slots()[0]);
            assertSame(unrelated, player.inventory().slots()[0]);
            assertThrows(IllegalArgumentException.class, () -> reloaded.register(id, player.player()));
        } finally {
            reloaded.close();
        }
    }

    private void audit() {
        f.tracking.reconcileAll();
        f.drain();
    }

    @Test
    void destructionBurstUsesOneTaskAndPreservesItemsWhoseDespawnWasCancelled() {
        var lost = new java.util.ArrayList<UUID>();
        var surviving = new java.util.ArrayList<UUID>();
        for (int index = 0; index < 32; index++) {
            UUID id = UUID.randomUUID();
            Item item = drop(f.mace(id, UUID.randomUUID()));
            for (int repeat = 0; repeat < 3; repeat++) {
                f.tracking.onDespawn(new ItemDespawnEvent(item, item.getLocation()));
            }
            if (index % 2 == 0) {
                when(item.isValid()).thenReturn(false);
                lost.add(id);
            } else {
                surviving.add(id);
            }
        }
        assertEquals(1, f.tasks.size(), "96 loss events should share one scheduled check");
        f.nextTick();
        audit();
        lost.forEach(id -> assertFalse(f.records.containsKey(id)));
        surviving.forEach(id -> assertTrue(f.records.containsKey(id)));
        assertEquals(16, f.records.size());
        // Finishing a batch must allow the next tick to schedule another check.
        Item later = drop(f.mace(UUID.randomUUID(), UUID.randomUUID()));
        f.tracking.onDespawn(new ItemDespawnEvent(later, later.getLocation()));
        assertEquals(1, f.tasks.size());
    }

    @Test
    void coalescingPreservesEveryIdentityObservedBeforeItemContentsChange() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Item item = drop(f.mace(first, UUID.randomUUID()));
        f.tracking.onDespawn(new ItemDespawnEvent(item, item.getLocation()));
        ItemStack replacement = f.mace(second, UUID.randomUUID());
        f.records.get(second).markDropped("world", 0, 0);
        when(item.getItemStack()).thenReturn(replacement);
        f.tracking.onDespawn(new ItemDespawnEvent(item, item.getLocation()));
        when(item.isValid()).thenReturn(false);
        assertEquals(1, f.tasks.size());
        f.nextTick();
        when(f.settings.hasMaceLimit()).thenReturn(false);
        audit();
        assertFalse(f.records.containsKey(first));
        assertFalse(f.records.containsKey(second));
    }

    @Test
    void findingOneCandidateInPlayersStillVerifiesOtherMissingIdentities() {
        var player = f.player("Owner");
        UUID moved = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        ItemStack mace = f.mace(moved, player.player().getUniqueId());
        f.mace(missing, player.player().getUniqueId());
        f.tracking.reconcileAll();
        f.scans.tick(1, Long.MAX_VALUE);
        player.inventory().slots()[0] = mace;
        f.drain();
        assertTrue(f.records.containsKey(moved));
        assertFalse(f.records.containsKey(missing));
        verify(f.world, times(2)).getLoadedChunks();
    }

    private Item drop(ItemStack stack) {
        return f.world.dropItemNaturally(f.at(1, 64, 1), stack);
    }

    private record Portable(ItemStack item, StorageTestSupport.Inv inside) { }

    private Portable shulker(ItemStack mace) {
        var inside = f.inventory(InventoryType.SHULKER_BOX, 27, null);
        inside.slots()[0] = mace;
        var state = mock(org.bukkit.block.ShulkerBox.class);
        when(state.getInventory()).thenReturn(inside.inventory());
        var meta = mock(BlockStateMeta.class);
        when(meta.getBlockState()).thenReturn(state);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.SHULKER_BOX);
        when(item.getAmount()).thenReturn(1);
        when(item.getItemMeta()).thenReturn(meta);
        return new Portable(item, inside);
    }
}
