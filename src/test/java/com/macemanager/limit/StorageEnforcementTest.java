package com.macemanager.limit;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageEnforcementTest {
    @TempDir Path directory;
    private final StorageTestSupport f = new StorageTestSupport();

    private record Portable(ItemStack item, Supplier<List<ItemStack>> contents) {
    }

    @Test
    void personalInventoriesAtSameReportedLocationStillDetectDistinctCopies() {
        var first = f.player("First");
        var second = f.player("Second");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        first.ender().slots()[0] = f.mace(id, owner);
        second.ender().slots()[0] = f.mace(id, owner);
        when(first.ender().inventory().getLocation()).thenReturn(f.at(1, 64, 1));
        when(second.ender().inventory().getLocation()).thenReturn(f.at(1, 64, 1));
        f.tracking.reconcileAll();
        f.drain();
        assertNotNull(first.ender().slots()[0]);
        assertNull(second.ender().slots()[0]);
        assertEquals(owner, f.records.get(id).holder());
        assertEquals(1, f.records.size());
    }

    @Test
    void samePersonalInventoryThroughDifferentWrappersKeepsItsMace() {
        var person = f.player("Viewer");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack mace = f.mace(id, owner);
        person.ender().slots()[0] = mace;
        when(person.ender().inventory().getLocation()).thenReturn(f.at(1, 64, 1));
        Inventory view = mock(Inventory.class);
        when(view.getType()).thenReturn(InventoryType.ENDER_CHEST);
        when(view.getSize()).thenReturn(27);
        when(view.getItem(anyInt())).thenAnswer(call -> person.ender().slots()[(int) call.getArgument(0)]);
        doAnswer(call -> {
            person.ender().slots()[(int) call.getArgument(0)] = call.getArgument(1);
            return null;
        }).when(view).setItem(anyInt(), nullable(ItemStack.class));
        when(person.view().getTopInventory()).thenReturn(view);
        f.tracking.reconcileAll();
        f.drain();
        assertSame(mace, person.ender().slots()[0]);
        assertEquals(owner, f.records.get(id).holder());
        verify(view, never()).setItem(anyInt(), nullable(ItemStack.class));
        verify(f.plugin, never()).destroyMace(id);
    }

    @Test
    void onlinePlayerInventoriesRemainObservableDuringEntityInvalidity() {
        var person = f.player("Owner");
        UUID carried = UUID.randomUUID();
        UUID stored = UUID.randomUUID();
        UUID owner = person.player().getUniqueId();
        person.inventory().slots()[0] = f.mace(carried, owner);
        person.ender().slots()[0] = f.mace(stored, owner);
        when(person.inventory().inventory().getHolder()).thenReturn(person.player());
        when(person.ender().inventory().getHolder()).thenReturn(person.player());
        when(person.player().isValid()).thenReturn(false);
        f.tracking.reconcileAll();
        f.drain();
        assertNotNull(person.inventory().slots()[0]);
        assertNotNull(person.ender().slots()[0]);
        assertTrue(f.records.containsKey(carried));
        assertTrue(f.records.containsKey(stored));
        verify(f.plugin, never()).destroyMace(any());
    }

    @Test
    void removedBlockSnapshotCannotMakeMovedMaceLookLikeDuplicate() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack mace = f.mace(id, owner);
        var source = f.inventory(InventoryType.CHEST, 27, f.at(1, 64, 1));
        var destination = f.inventory(InventoryType.CHEST, 27, f.at(2, 64, 1));
        source.slots()[0] = mace;
        f.allowed.add("chests");
        f.chunk(0, 0, new BlockState[]{f.blockInventory(source, 1, 64, 1), f.blockInventory(destination, 2, 64, 1)});
        f.tracking.reconcileAll();
        f.scans.tick(3, Long.MAX_VALUE); // Enumerate chunk, query ground entities, inspect source.
        f.blocks.remove(List.of(1, 64, 1)); // Old inventory object still has its stale item snapshot.
        destination.slots()[0] = mace;
        f.drain();
        assertSame(mace, destination.slots()[0]);
        assertEquals(2, f.records.get(id).location().blockX());
        assertEquals(owner, f.records.get(id).holder());
        verify(f.plugin, never()).destroyMace(id);
    }

    @Test
    void nestedCopiesAreRemovedWithoutStrippingUnrelatedContents() {
        var person = f.player("Viewer");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack marker = mock(ItemStack.class);
        when(marker.getType()).thenReturn(Material.STICK);
        Portable first = bundle(f.mace(id, owner));
        Portable duplicate = shulker(marker, f.mace(id, owner));
        person.ender().slots()[0] = first.item();
        person.ender().slots()[1] = duplicate.item();
        f.allowed.addAll(List.of("bundles", "shulker-boxes"));
        f.tracking.reconcileAll();
        f.drain();
        assertEquals(1, count(first, id));
        assertEquals(0, count(duplicate, id));
        assertSame(marker, duplicate.contents().get().getFirst());
        assertSame(first.item(), person.ender().slots()[0]);
        assertSame(duplicate.item(), person.ender().slots()[1]);
        assertEquals(owner, f.records.get(id).holder());
    }

    @Test
    void repeatedPathsToDeepPortableContentsDoNotRemoveOriginal() {
        var first = f.player("FirstViewer");
        var second = f.player("SecondViewer");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Portable inside = bundle(f.mace(id, owner));
        Portable outside = shulker(inside.item());
        var chest = f.inventory(InventoryType.CHEST, 27, f.at(1, 64, 1));
        chest.slots()[3] = outside.item();
        when(first.view().getTopInventory()).thenReturn(chest.inventory());
        when(second.view().getTopInventory()).thenReturn(chest.inventory());
        f.allowed.addAll(List.of("chests", "bundles", "shulker-boxes"));
        f.chunk(0, 0, new BlockState[]{f.blockInventory(chest, 1, 64, 1)});
        f.tracking.reconcileAll();
        f.drain();
        f.tracking.reconcileAll();
        f.drain();
        assertEquals(1, count(inside, id));
        assertSame(outside.item(), chest.slots()[3]);
        assertEquals(owner, f.records.get(id).holder());
        assertEquals(MaceLocation.State.STORED, f.records.get(id).location().state());
        verify(f.plugin, never()).destroyMace(id);
    }

    @Test
    void stackedPortableCopiesAreReducedButEmptyAndAdminStorageArePreserved() {
        var person = f.player("Viewer");
        UUID id = UUID.randomUUID();
        Portable tracked = bundle(f.mace(id, UUID.randomUUID()));
        Portable empty = shulker();
        ItemStack admin = mock(ItemStack.class);
        when(admin.getType()).thenReturn(Material.MACE);
        f.classifications.put(admin, new MaceItems.Classification(true, true, null));
        Portable exempt = bundle(admin);
        amount(tracked.item(), 3);
        amount(empty.item(), 3);
        amount(exempt.item(), 3);
        person.ender().slots()[0] = tracked.item();
        person.ender().slots()[1] = empty.item();
        person.ender().slots()[2] = exempt.item();
        f.allowed.addAll(List.of("bundles", "shulker-boxes"));
        f.tracking.reconcileAll();
        f.drain();
        assertEquals(1, tracked.item().getAmount());
        assertEquals(1, count(tracked, id));
        assertEquals(3, empty.item().getAmount());
        assertEquals(3, exempt.item().getAmount());
        assertSame(admin, exempt.contents().get().getFirst());
        assertEquals(1, f.records.size());
    }

    @Test
    void groundStacksCannotHideCopiesOfDirectOrNestedTrackedMaces() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        ItemStack direct = f.mace(firstId, UUID.randomUUID());
        Portable portable = shulker(bundle(f.mace(secondId, UUID.randomUUID())).item());
        amount(direct, 4);
        amount(portable.item(), 4);
        ground(direct);
        ground(portable.item());
        f.allowed.addAll(List.of("bundles", "shulker-boxes"));
        f.tracking.reconcileAll();
        f.drain();
        assertEquals(1, direct.getAmount());
        assertEquals(1, portable.item().getAmount());
        assertTrue(f.records.get(firstId).dropped());
        assertEquals(MaceLocation.State.STORED, f.records.get(secondId).location().state());
        assertEquals(2, f.records.size());
    }

    @Test
    void invalidIdsInsideAllowedContainersAreRemovedWithoutAffectingValidMace() {
        var person = f.player("Viewer");
        UUID valid = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack illegal = f.mace(unknown, owner);
        f.records.remove(unknown);
        Portable portable = shulker(bundle(illegal, f.mace(valid, owner)).item());
        person.ender().slots()[0] = portable.item();
        f.allowed.addAll(List.of("bundles", "shulker-boxes"));
        f.tracking.reconcileAll();
        f.drain();
        assertTrue(NestedMaces.contains(portable.item(), item -> valid.equals(f.items.trackedId(item))));
        assertFalse(NestedMaces.contains(portable.item(), item -> unknown.equals(f.items.trackedId(item))));
        assertEquals(owner, f.records.get(valid).holder());
        assertEquals(1, f.records.size());
    }

    @Test
    void storedAndOfflineMacesStillBlockCraftingAfterDatabaseReload() {
        var person = f.player("Owner");
        UUID owner = person.player().getUniqueId();
        List<UUID> ids = java.util.stream.IntStream.range(0, 4).mapToObj(index -> UUID.randomUUID()).toList();
        Path database = directory.resolve("maces.db");
        MaceRegistry real = new MaceRegistry(database.toFile(), Logger.getLogger("StoredCapTest"), task -> {});
        real.load();
        when(f.plugin.registry()).thenReturn(real);
        try {
            for (UUID id : ids) real.register(id, person.player());
            person.ender().slots()[0] = f.mace(ids.get(0), owner);
            person.ender().slots()[1] = shulker(f.mace(ids.get(1), owner)).item();
            person.ender().slots()[2] = bundle(f.mace(ids.get(2), owner)).item();
            real.locate(ids.get(3), MaceLocation.playerStorage("ENDER_CHEST", UUID.randomUUID()));
            f.allowed.addAll(List.of("bundles", "shulker-boxes"));
            f.tracking.reconcileAll();
            f.drain();
            assertEquals(4, real.count());
            assertTrue(real.all().stream().allMatch(record -> record.location().state() == MaceLocation.State.STORED));
            verify(f.plugin, never()).destroyMace(any());
        } finally {
            real.close();
        }
        MaceRegistry reloaded = new MaceRegistry(database.toFile(), Logger.getLogger("StoredCapTest"), task -> {});
        reloaded.load();
        when(f.plugin.registry()).thenReturn(reloaded);
        try {
            assertEquals(4, reloaded.count());
            when(f.settings.allowedMaces()).thenReturn(4);
            CraftingInventory crafting = mock(CraftingInventory.class);
            ItemStack result = mock(ItemStack.class);
            when(result.getType()).thenReturn(Material.MACE);
            when(result.getAmount()).thenReturn(1);
            when(crafting.getResult()).thenReturn(result);
            PrepareItemCraftEvent preview = mock(PrepareItemCraftEvent.class);
            when(preview.getInventory()).thenReturn(crafting);
            CraftingListener listener = new CraftingListener(f.plugin);
            listener.onPrepare(preview);
            verify(crafting).setResult(null);

            ItemStack ingredient = mock(ItemStack.class);
            when(ingredient.getType()).thenReturn(mock(Material.class));
            when(ingredient.getAmount()).thenReturn(1);
            when(crafting.getMatrix()).thenReturn(new ItemStack[]{ingredient});
            when(person.player().getInventory().getStorageContents()).thenReturn(new ItemStack[36]);
            Recipe recipe = mock(Recipe.class);
            when(recipe.getResult()).thenReturn(result);
            CraftItemEvent craft = mock(CraftItemEvent.class);
            when(craft.getWhoClicked()).thenReturn(person.player());
            when(craft.getInventory()).thenReturn(crafting);
            when(craft.getRecipe()).thenReturn(recipe);
            when(craft.isShiftClick()).thenReturn(true);
            listener.onCraft(craft);
            verify(craft).setCancelled(true);
            verify(f.items, never()).createTracked();
            assertEquals(4, reloaded.count());
        } finally {
            reloaded.close();
        }
    }

    private long count(Portable portable, UUID id) {
        return portable.contents().get().stream().filter(item -> id.equals(f.items.trackedId(item))).count();
    }

    private Portable bundle(ItemStack... children) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.BUNDLE);
        when(item.getAmount()).thenReturn(1);
        BundleMeta meta = mock(BundleMeta.class);
        when(item.getItemMeta()).thenReturn(meta);
        AtomicReference<List<ItemStack>> contents = new AtomicReference<>(new ArrayList<>(Arrays.asList(children)));
        when(meta.getItems()).thenAnswer(call -> new ArrayList<>(contents.get()));
        doAnswer(call -> { contents.set(new ArrayList<>(call.getArgument(0))); return null; }).when(meta).setItems(anyList());
        return new Portable(item, () -> contents.get());
    }

    private Portable shulker(ItemStack... children) {
        var contents = f.inventory(InventoryType.SHULKER_BOX, 27, null);
        System.arraycopy(children, 0, contents.slots(), 0, children.length);
        var state = mock(org.bukkit.block.ShulkerBox.class);
        when(state.getInventory()).thenReturn(contents.inventory());
        BlockStateMeta meta = mock(BlockStateMeta.class);
        when(meta.getBlockState()).thenReturn(state);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.RED_SHULKER_BOX);
        when(item.getAmount()).thenReturn(1);
        when(item.getItemMeta()).thenReturn(meta);
        return new Portable(item, () -> Arrays.asList(contents.slots()));
    }

    private void amount(ItemStack item, int initial) {
        AtomicInteger amount = new AtomicInteger(initial);
        when(item.getAmount()).thenAnswer(call -> amount.get());
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
    }

    private Item ground(ItemStack stack) {
        Item item = mock(Item.class);
        when(item.getType()).thenReturn(EntityType.ITEM);
        when(item.getUniqueId()).thenReturn(UUID.randomUUID());
        when(item.isValid()).thenReturn(true);
        when(item.getLocation()).thenReturn(f.at(1, 64, 1));
        AtomicReference<ItemStack> contents = new AtomicReference<>(stack);
        when(item.getItemStack()).thenAnswer(call -> contents.get());
        doAnswer(call -> { contents.set(call.getArgument(0)); return null; }).when(item).setItemStack(any());
        f.ground.add(item);
        return item;
    }
}
