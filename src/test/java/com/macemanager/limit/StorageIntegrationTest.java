package com.macemanager.limit;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageIntegrationTest {
    private final StorageTestSupport f = new StorageTestSupport();

    @Test
    void enderChestAndMultipleSharedViewersRetainOriginalOwnerAndStoredItems() {
        var first = f.player("ViewerOne");
        var second = f.player("ViewerTwo");
        UUID owner = UUID.randomUUID();
        UUID chestId = UUID.randomUUID();
        UUID enderId = UUID.randomUUID();
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(2, 64, 2));
        chest.slots()[3] = f.mace(chestId, owner);
        first.ender().slots()[4] = f.mace(enderId, owner);
        when(first.view().getTopInventory()).thenReturn(chest.inventory());
        when(second.view().getTopInventory()).thenReturn(chest.inventory());
        f.allowed.add("chests");
        f.chunk(0, 0, new BlockState[]{f.blockInventory(chest, 2, 64, 2)});

        f.tracking.reconcileAll();
        f.drain();

        assertNotNull(chest.slots()[3]);
        assertNotNull(first.ender().slots()[4]);
        assertEquals(owner, f.records.get(chestId).holder());
        assertEquals(owner, f.records.get(enderId).holder());
        assertEquals(MaceLocation.State.STORED, f.records.get(chestId).location().state());
        assertEquals(first.player().getUniqueId(), f.records.get(enderId).location().inventoryOwner());
        verify(f.registry, never()).hold(eq(chestId), any());
        verify(f.plugin, never()).destroyMace(any());
    }

    @Test
    void doubleChestViewsOfEitherHalfAreTheSamePhysicalSlots() {
        f.allowed.add("chests");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var left = f.inventory(InventoryType.CHEST, 27, f.at(15, 64, 1));
        var right = f.inventory(InventoryType.CHEST, 27, f.at(16, 64, 1));
        right.slots()[3] = f.mace(id, owner);
        var combined = mock(org.bukkit.inventory.DoubleChestInventory.class);
        when(combined.getType()).thenReturn(InventoryType.CHEST);
        when(combined.getSize()).thenReturn(54);
        when(combined.getLocation()).thenReturn(f.at(15, 64, 1));
        when(combined.getLeftSide()).thenReturn(left.inventory());
        when(combined.getRightSide()).thenReturn(right.inventory());
        var viewer = f.player("Viewer");
        when(viewer.view().getTopInventory()).thenReturn(combined);
        f.chunk(0, 0, new BlockState[]{f.blockInventory(left, 15, 64, 1)});
        f.chunk(1, 0, new BlockState[]{f.blockInventory(right, 16, 64, 1)});
        f.tracking.reconcileAll();
        f.drain();
        assertNotNull(right.slots()[3]);
        assertEquals(owner, f.records.get(id).holder());
        assertEquals(16, f.records.get(id).location().blockX());
        verify(f.plugin, never()).destroyMace(id);
    }

    @Test
    void blockChestScansUseOnlyLoadedHalfWithoutResolvingNeighbour() {
        f.allowed.add("chests");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        f.mace(id, owner);
        f.records.get(id).setLocation(MaceLocation.block(MaceLocation.State.STORED, "CHEST", "world", 16, 64, 1));
        when(f.world.isChunkLoaded(1, 0)).thenReturn(false);
        var left = f.inventory(InventoryType.CHEST, 27, f.at(15, 64, 1));
        var state = mock(org.bukkit.block.Chest.class);
        var block = mock(org.bukkit.block.Block.class);
        when(state.getBlock()).thenReturn(block);
        when(state.getLocation()).thenReturn(f.at(15, 64, 1));
        when(block.getLocation()).thenReturn(f.at(15, 64, 1));
        when(block.getState()).thenReturn(state);
        when(state.getBlockInventory()).thenReturn(left.inventory());
        when(state.getInventory()).thenThrow(new AssertionError("Would resolve a combined chest"));
        f.chunk(0, 0, new BlockState[]{state});
        f.tracking.reconcileAll();
        f.drain();
        assertTrue(f.records.containsKey(id));
        assertEquals(owner, f.records.get(id).holder());
        verify(state, never()).getInventory();
        verify(f.world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void shelvesWithoutContainerInterfaceAndFramesSurviveAudits() {
        UUID owner = UUID.randomUUID();
        UUID shelfId = UUID.randomUUID();
        UUID frameId = UUID.randomUUID();
        InventoryType shelfType = mock(InventoryType.class);
        when(shelfType.name()).thenReturn("SHELF");
        var shelf = f.inventory(shelfType, 3, f.at(1, 64, 1));
        shelf.slots()[0] = f.mace(shelfId, owner);
        ItemFrame frame = frame(f.mace(frameId, owner));
        f.chunk(0, 0, new BlockState[]{f.blockInventory(shelf, 1, 64, 1)}, frame);
        f.tracking.reconcileAll();
        f.drain();
        assertNotNull(shelf.slots()[0]);
        verify(frame, never()).setItem(any());
        assertEquals(owner, f.records.get(frameId).holder());
        assertEquals(MaceLocation.State.DISPLAYED, f.records.get(frameId).location().state());
        assertEquals(frame.getUniqueId(), f.records.get(frameId).location().entity());
        verify(f.plugin, never()).destroyMace(any());
    }

    @Test
    void actualDuplicateAcrossCarriedAndStoredSlotsIsRemoved() {
        var person = f.player("Owner");
        UUID id = UUID.randomUUID();
        ItemStack carried = f.mace(id, person.player().getUniqueId());
        ItemStack duplicate = f.mace(id, person.player().getUniqueId());
        person.inventory().slots()[0] = carried;
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(1, 64, 1));
        chest.slots()[0] = duplicate;
        f.allowed.add("chests");
        f.chunk(0, 0, new BlockState[]{f.blockInventory(chest, 1, 64, 1)});
        f.tracking.reconcileAll();
        f.drain();
        assertSame(carried, person.inventory().slots()[0]);
        assertNull(chest.slots()[0]);
        assertTrue(f.records.containsKey(id));
        assertEquals(MaceLocation.State.CARRIED, f.records.get(id).location().state());
    }

    @Test
    void movingBetweenAuditUnitsIsNotMisclassifiedAsDuplication() {
        var person = f.player("Owner");
        UUID id = UUID.randomUUID();
        ItemStack mace = f.mace(id, person.player().getUniqueId());
        person.inventory().slots()[0] = mace;
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(1, 64, 1));
        f.allowed.add("chests");
        f.chunk(0, 0, new BlockState[]{f.blockInventory(chest, 1, 64, 1)});
        f.tracking.reconcileAll();
        f.scans.tick(1, Long.MAX_VALUE); // Player inventory was seen before this move.
        person.inventory().slots()[0] = null;
        chest.slots()[0] = mace;
        f.tracking.touch(mace);
        f.drain();
        assertSame(mace, chest.slots()[0]);
        assertEquals(MaceLocation.State.STORED, f.records.get(id).location().state());
        assertEquals(person.player().getUniqueId(), f.records.get(id).holder());
    }

    @Test
    void moveBehindScannerDefersOnlyThatMacesMissingDecision() {
        var person = f.player("Owner");
        UUID movingId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        ItemStack mace = f.mace(movingId, person.player().getUniqueId());
        f.mace(missingId, person.player().getUniqueId());
        f.tracking.reconcileAll();
        f.scans.tick(1, Long.MAX_VALUE); // Empty player was scanned.
        person.inventory().slots()[0] = mace;
        f.tracking.touch(mace);
        f.drain();
        assertTrue(f.records.containsKey(movingId));
        assertFalse(f.records.containsKey(missingId));
        f.tracking.reconcileAll();
        f.drain();
        assertTrue(f.records.containsKey(movingId));
    }

    @Test
    void loadedMissingStorageIsRemovedButUnloadedAndOfflineStorageArePreserved() {
        var owner = f.player("Owner");
        UUID missing = UUID.randomUUID();
        UUID unloaded = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        f.mace(missing, owner.player().getUniqueId());
        f.mace(unloaded, owner.player().getUniqueId());
        f.mace(offline, owner.player().getUniqueId());
        f.records.get(missing).setLocation(MaceLocation.block(MaceLocation.State.STORED, "CHEST", "world", 1, 64, 1));
        f.records.get(unloaded).setLocation(MaceLocation.block(MaceLocation.State.STORED, "CHEST", "world", 64, 64, 1));
        f.records.get(offline).setLocation(MaceLocation.playerStorage("ENDER_CHEST", UUID.randomUUID()));
        when(f.world.isChunkLoaded(4, 0)).thenReturn(false);
        f.tracking.reconcileAll();
        f.drain();
        assertFalse(f.records.containsKey(missing));
        assertTrue(f.records.containsKey(unloaded));
        assertTrue(f.records.containsKey(offline));
        verify(f.world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void unloadingCapturesLastPlacementBeforeQueuedScanRuns() {
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(1, 64, 1));
        chest.slots()[0] = f.mace(id, owner);
        f.allowed.add("chests");
        Chunk chunk = f.chunk(0, 0, new BlockState[]{f.blockInventory(chest, 1, 64, 1)});
        f.tracking.beforeUnload(chunk);
        when(chunk.isLoaded()).thenReturn(false);
        when(f.world.isChunkLoaded(0, 0)).thenReturn(false);
        f.tracking.reconcileAll();
        f.drain();
        assertTrue(f.records.containsKey(id));
        assertEquals(owner, f.records.get(id).holder());
        assertEquals(MaceLocation.State.STORED, f.records.get(id).location().state());
    }

    @Test
    void disabledStorageEjectsValidMaceAndInvalidStoredIdsAreDeleted() {
        UUID id = UUID.randomUUID();
        UUID invalid = UUID.randomUUID();
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(1, 64, 1));
        chest.slots()[0] = f.mace(id, UUID.randomUUID());
        chest.slots()[1] = f.mace(invalid, UUID.randomUUID());
        f.records.remove(invalid);
        f.chunk(0, 0, new BlockState[]{f.blockInventory(chest, 1, 64, 1)});
        f.tracking.reconcileAll();
        f.drain();
        assertNull(chest.slots()[0]);
        assertNull(chest.slots()[1]);
        assertEquals(1, f.ground.size());
        assertEquals(id, f.items.trackedId(f.ground.getFirst().getItemStack()));
        assertTrue(f.records.get(id).dropped());
    }

    @Test
    void allowedPortableContentsRetainOwnerAndCanBeSweptFromDisplays() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack mace = f.mace(id, owner);
        ItemStack bundle = mock(ItemStack.class);
        when(bundle.getType()).thenReturn(Material.BUNDLE);
        BundleMeta meta = mock(BundleMeta.class);
        when(bundle.getItemMeta()).thenReturn(meta);
        AtomicReference<List<ItemStack>> contents = new AtomicReference<>(new ArrayList<>(List.of(mace)));
        when(meta.getItems()).thenAnswer(call -> List.copyOf(contents.get()));
        doAnswer(call -> { contents.set(call.getArgument(0)); return null; }).when(meta).setItems(anyList());
        ItemFrame frame = frame(bundle);
        f.allowed.add("bundles");
        f.chunk(0, 0, new BlockState[0], frame);
        f.tracking.reconcileAll();
        f.drain();
        assertEquals(List.of(mace), contents.get());
        assertEquals(owner, f.records.get(id).holder());
        assertEquals(frame.getUniqueId(), f.records.get(id).location().entity());
        AtomicInteger removed = new AtomicInteger();
        new MaceSweeper(f.plugin).remove(stack -> id.equals(f.items.trackedId(stack)), removed::set,
                () -> fail("Sweep failed"));
        f.drain();
        assertEquals(1, removed.get());
        assertTrue(contents.get().isEmpty());
    }

    @Test
    void removalSweepIncludesShelfInventoryHolders() {
        UUID id = UUID.randomUUID();
        var shelf = f.inventory(InventoryType.CHEST, 3, f.at(1, 64, 1));
        shelf.slots()[0] = f.mace(id, UUID.randomUUID());
        f.chunk(0, 0, new BlockState[]{f.blockInventory(shelf, 1, 64, 1)});
        AtomicInteger removed = new AtomicInteger();
        new MaceSweeper(f.plugin).remove(stack -> id.equals(f.items.trackedId(stack)), removed::set,
                () -> fail("Sweep failed"));
        f.drain();
        assertEquals(1, removed.get());
        assertNull(shelf.slots()[0]);
    }

    @Test
    void silentMoveBehindScannerGetsFinalVerificationBeforeInvalidation() {
        var person = f.player("Owner");
        UUID id = UUID.randomUUID();
        ItemStack mace = f.mace(id, person.player().getUniqueId());
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(1, 64, 1));
        chest.slots()[0] = mace;
        f.allowed.add("chests");
        f.chunk(0, 0, new BlockState[]{f.blockInventory(chest, 1, 64, 1)});
        f.records.get(id).setLocation(MaceLocation.block(MaceLocation.State.STORED, "CHEST", "world", 1, 64, 1));
        f.tracking.reconcileAll();
        f.scans.tick(1, Long.MAX_VALUE); // Player was scanned while the mace was in the chest.
        chest.slots()[0] = null;
        person.inventory().slots()[0] = mace; // A plugin directly changes slots: no events/touch.
        f.drain();
        assertTrue(f.records.containsKey(id));
        assertSame(mace, person.inventory().slots()[0]);
        assertEquals(MaceLocation.State.CARRIED, f.records.get(id).location().state());
        // The mandatory audit still scans the world once; player verification settles
        // this missing candidate without another world-wide inventory/entity query.
        verify(f.world, times(1)).getLoadedChunks();
        verify(f.world, times(1)).getEntitiesByClass(org.bukkit.entity.Item.class);
    }

    @Test
    void retrievalTransfersOwnershipButOpeningStorageDoesNot() {
        var viewer = f.player("NewOwner");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack mace = f.mace(id, owner);
        viewer.ender().slots()[0] = mace;
        f.tracking.markDirty(viewer.player());
        f.tracking.reconcileDirty();
        assertEquals(owner, f.records.get(id).holder());
        viewer.ender().slots()[0] = null;
        viewer.inventory().slots()[0] = mace;
        f.tracking.markDirty(viewer.player());
        f.tracking.reconcileDirty();
        assertEquals(viewer.player().getUniqueId(), f.records.get(id).holder());
        assertEquals(MaceLocation.State.CARRIED, f.records.get(id).location().state());
    }

    @Test
    void cancelledPickupLeavesOwnershipUnchanged() {
        var viewer = f.player("WouldBeOwner");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack mace = f.mace(id, owner);
        var item = mock(org.bukkit.entity.Item.class);
        when(item.getItemStack()).thenReturn(mace);
        var event = new org.bukkit.event.entity.EntityPickupItemEvent(viewer.player(), item, 0);
        f.tracking.onPickup(event);
        event.setCancelled(true); // Another plugin cancels after the HIGH validation handler.
        assertEquals(owner, f.records.get(id).holder());
    }

    @Test
    void hopperTransferQueuesActualResultAndRetainsOwner() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack mace = f.mace(id, owner);
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(1, 64, 1));
        var hopper = f.inventory(InventoryType.HOPPER, 5, f.at(1, 63, 1));
        chest.slots()[0] = mace;
        f.allowed.add("chests");
        f.allowed.add("hoppers");
        f.chunk(0, 0, new BlockState[]{f.blockInventory(chest, 1, 64, 1), f.blockInventory(hopper, 1, 63, 1)});
        var event = mock(org.bukkit.event.inventory.InventoryMoveItemEvent.class);
        when(event.getItem()).thenReturn(mace);
        when(event.getSource()).thenReturn(chest.inventory());
        when(event.getDestination()).thenReturn(hopper.inventory());
        new ContainerListener(f.plugin).afterMove(event);
        chest.slots()[0] = null;
        hopper.slots()[0] = mace;
        f.drain();
        assertSame(mace, hopper.slots()[0]);
        assertEquals(owner, f.records.get(id).holder());
        assertEquals("HOPPER", f.records.get(id).location().storageType());
        assertEquals(63, f.records.get(id).location().blockY());
    }

    @Test
    void allowedShulkerContentsStillRespectBlockedOuterStorage() {
        var carrier = f.player("Carrier");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack mace = f.mace(id, owner);
        var inside = f.inventory(InventoryType.SHULKER_BOX, 27, null);
        inside.slots()[0] = mace;
        var box = mock(org.bukkit.block.ShulkerBox.class);
        when(box.getInventory()).thenReturn(inside.inventory());
        var meta = mock(org.bukkit.inventory.meta.BlockStateMeta.class);
        when(meta.getBlockState()).thenReturn(box);
        ItemStack shulker = mock(ItemStack.class);
        when(shulker.getType()).thenReturn(Material.RED_SHULKER_BOX);
        when(shulker.getItemMeta()).thenReturn(meta);
        carrier.inventory().slots()[0] = shulker;
        f.allowed.add("shulker-boxes");
        f.tracking.markDirty(carrier.player());
        f.tracking.reconcileDirty();
        assertEquals(owner, f.records.get(id).holder());
        assertSame(mace, inside.slots()[0]);
        assertEquals(carrier.player().getUniqueId(), f.records.get(id).location().inventoryOwner());
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(1, 64, 1));
        f.chunk(0, 0, new BlockState[]{f.blockInventory(chest, 1, 64, 1)});
        carrier.inventory().slots()[0] = null;
        chest.slots()[0] = shulker;
        f.tracking.reconcileAll();
        f.drain();
        assertSame(shulker, chest.slots()[0]);
        assertNull(inside.slots()[0]);
        assertEquals(1, f.ground.size());
        assertTrue(f.records.get(id).dropped());
        assertEquals(owner, f.records.get(id).holder());
    }

    @Test
    void failedStorageScanDefersMissingDecisionsAndRequestsRetry() {
        var owner = f.player("Owner");
        UUID id = UUID.randomUUID();
        f.mace(id, owner.player().getUniqueId());
        Chunk chunk = f.chunk(0, 0, new BlockState[0]);
        when(chunk.getTileEntities()).thenThrow(new IllegalStateException("Simulated unavailable chunk"));
        f.tracking.reconcileAll();
        f.drain();
        assertTrue(f.records.containsKey(id));
        assertTrue(f.tracking.auditDue());
        assertTrue(f.tracking.readyForAudit());
        verify(f.plugin, never()).destroyMace(any());
    }

    @Test
    void quittingImmediatelyAfterEnderStorageKeepsOwnerAndPersonalLocation() {
        var viewer = f.player("Viewer");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        viewer.ender().slots()[0] = f.mace(id, owner);
        var event = mock(org.bukkit.event.player.PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(viewer.player());
        f.tracking.playerQuit(event);
        when(viewer.player().isOnline()).thenReturn(false);
        f.tracking.reconcileAll();
        f.drain();
        assertEquals(owner, f.records.get(id).holder());
        assertEquals(viewer.player().getUniqueId(), f.records.get(id).location().inventoryOwner());
        assertEquals("ENDER_CHEST", f.records.get(id).location().storageType());
        verify(f.registry, never()).hold(eq(id), any());
    }

    @Test
    void nestedMacesCannotBypassMobPickupProtectionAndDespawnReleasesTheirRecords() {
        UUID id = UUID.randomUUID();
        ItemStack mace = f.mace(id, UUID.randomUUID());
        ItemStack bundle = mock(ItemStack.class);
        when(bundle.getType()).thenReturn(Material.BUNDLE);
        BundleMeta meta = mock(BundleMeta.class);
        when(bundle.getItemMeta()).thenReturn(meta);
        when(meta.getItems()).thenReturn(List.of(mace));
        var item = mock(org.bukkit.entity.Item.class);
        when(item.getItemStack()).thenReturn(bundle);
        when(item.getLocation()).thenReturn(f.at(1, 64, 1));
        var pickup = new org.bukkit.event.entity.EntityPickupItemEvent(mock(org.bukkit.entity.Zombie.class), item, 0);
        f.tracking.onPickup(pickup);
        assertTrue(pickup.isCancelled());
        f.records.get(id).markDropped("world", 0, 0);
        f.tracking.onDespawn(new org.bukkit.event.entity.ItemDespawnEvent(item, item.getLocation()));
        f.nextTick();
        f.tracking.reconcileAll();
        f.drain();
        assertFalse(f.records.containsKey(id));
    }

    @Test
    void damageFollowedByHopperPickupDoesNotInvalidateStoredMace() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack mace = f.mace(id, owner);
        f.records.get(id).markDropped("world", 0, 0);
        var item = mock(org.bukkit.entity.Item.class);
        when(item.getItemStack()).thenReturn(mace);
        when(item.isValid()).thenReturn(true);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(f.server.getScheduler()).thenReturn(scheduler);
        List<Runnable> tasks = new ArrayList<>();
        when(scheduler.runTask(eq(f.plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return mock(org.bukkit.scheduler.BukkitTask.class);
        });
        var damage = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        when(damage.getEntity()).thenReturn(item);
        f.tracking.onItemDamage(damage);
        var hopper = f.inventory(InventoryType.HOPPER, 5, f.at(1, 63, 1));
        f.allowed.add("hoppers");
        f.chunk(0, 0, new BlockState[]{f.blockInventory(hopper, 1, 63, 1)});
        hopper.slots()[0] = mace; // Hopper removed the ground entity after the damage event.
        when(item.isValid()).thenReturn(false);
        tasks.forEach(Runnable::run);
        f.tracking.reconcileAll();
        f.drain();
        assertTrue(f.records.containsKey(id));
        assertSame(mace, hopper.slots()[0]);
        assertEquals(owner, f.records.get(id).holder());
        assertEquals("HOPPER", f.records.get(id).location().storageType());
    }

    private ItemFrame frame(ItemStack item) {
        ItemFrame frame = mock(ItemFrame.class);
        when(frame.getType()).thenReturn(EntityType.ITEM_FRAME);
        when(frame.getUniqueId()).thenReturn(UUID.randomUUID());
        when(frame.getLocation()).thenReturn(f.at(2, 65, 2));
        when(frame.isValid()).thenReturn(true);
        AtomicReference<ItemStack> contents = new AtomicReference<>(item);
        when(frame.getItem()).thenAnswer(call -> contents.get());
        doAnswer(call -> { contents.set(call.getArgument(0)); return null; }).when(frame).setItem(any());
        return frame;
    }
}
