package com.macemanager.limit;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;

class ContainerListenerTest {
    private final StorageTestSupport f = new StorageTestSupport();
    private final StorageTestSupport.Person person = f.player("Player");
    private final ItemStack mace = f.mace(UUID.randomUUID(), person.player().getUniqueId());
    private final StorageTestSupport.Inv chest = f.inventory(InventoryType.CHEST, 27, f.at(1, 64, 1));
    private final ContainerListener listener = new ContainerListener(f.plugin);

    @Test
    void placementAndCursorSwapFollowReloadedRulesWithoutNeedingACap() {
        when(f.settings.hasMaceLimit()).thenReturn(false);
        for (InventoryAction action : new InventoryAction[]{InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE,
                InventoryAction.PLACE_SOME, InventoryAction.SWAP_WITH_CURSOR}) {
            var blocked = click(action, chest.inventory());
            when(blocked.getCursor()).thenReturn(mace);
            listener.onClick(blocked);
            verify(blocked).setCancelled(true);
        }
        f.allowed.add("chests");
        var allowed = click(InventoryAction.PLACE_ALL, chest.inventory());
        when(allowed.getCursor()).thenReturn(mace);
        listener.onClick(allowed);
        verify(allowed, never()).setCancelled(true);
    }

    @Test
    void shiftClickUsesActualDestinationAndPermitsRetrievalFromBlockedStorage() {
        when(person.view().getTopInventory()).thenReturn(chest.inventory());
        var deposit = click(InventoryAction.MOVE_TO_OTHER_INVENTORY, person.inventory().inventory());
        when(deposit.getCurrentItem()).thenReturn(mace);
        listener.onClick(deposit);
        verify(deposit).setCancelled(true);
        var retrieve = click(InventoryAction.MOVE_TO_OTHER_INVENTORY, chest.inventory());
        when(retrieve.getCurrentItem()).thenReturn(mace);
        listener.onClick(retrieve);
        verify(retrieve, never()).setCancelled(true);
        f.allowed.add("chests");
        var allowed = click(InventoryAction.MOVE_TO_OTHER_INVENTORY, person.inventory().inventory());
        when(allowed.getCurrentItem()).thenReturn(mace);
        listener.onClick(allowed);
        verify(allowed, never()).setCancelled(true);
    }

    @Test
    void numberKeyAndOffhandSwapsCannotBypassBlockedDestination() {
        person.inventory().slots()[2] = mace;
        var hotbar = click(InventoryAction.HOTBAR_SWAP, chest.inventory());
        when(hotbar.getHotbarButton()).thenReturn(2);
        listener.onClick(hotbar);
        verify(hotbar).setCancelled(true);
        when(person.player().getInventory().getItemInOffHand()).thenReturn(mace);
        var offhand = click(InventoryAction.HOTBAR_SWAP, chest.inventory());
        when(offhand.getClick()).thenReturn(ClickType.SWAP_OFFHAND);
        listener.onClick(offhand);
        verify(offhand).setCancelled(true);
        f.allowed.add("chests");
        var allowed = click(InventoryAction.HOTBAR_SWAP, chest.inventory());
        when(allowed.getHotbarButton()).thenReturn(2);
        listener.onClick(allowed);
        verify(allowed, never()).setCancelled(true);
    }

    @Test
    void dragChecksEveryDestinationSlot() {
        when(person.view().getTopInventory()).thenReturn(chest.inventory());
        var event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(person.view());
        when(event.getWhoClicked()).thenReturn(person.player());
        when(event.getOldCursor()).thenReturn(mace);
        when(event.getRawSlots()).thenReturn(Set.of(0, 30));
        listener.onDrag(event);
        verify(event).setCancelled(true);
        clearInvocations(event);
        f.allowed.add("chests");
        listener.onDrag(event);
        verify(event, never()).setCancelled(true);
        f.allowed.remove("chests");
        when(event.getRawSlots()).thenReturn(Set.of(30));
        listener.onDrag(event);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void hoppersCanMoveIntoAllowedStorageAndExtractFromBlockedStorage() {
        var hopper = f.inventory(InventoryType.HOPPER, 5, f.at(1, 63, 1));
        var transfer = mock(InventoryMoveItemEvent.class);
        when(transfer.getItem()).thenReturn(mace);
        when(transfer.getSource()).thenReturn(chest.inventory());
        when(transfer.getDestination()).thenReturn(hopper.inventory());
        listener.onHopperMove(transfer);
        verify(transfer).setCancelled(true);
        f.allowed.add("hoppers");
        clearInvocations(transfer);
        listener.onHopperMove(transfer);
        verify(transfer, never()).setCancelled(true);
        Item dropped = mock(Item.class);
        when(dropped.getItemStack()).thenReturn(mace);
        var pickup = new InventoryPickupItemEvent(hopper.inventory(), dropped);
        listener.onHopperPickup(pickup);
        org.junit.jupiter.api.Assertions.assertFalse(pickup.isCancelled());
        f.allowed.remove("hoppers");
        listener.onHopperPickup(pickup);
        org.junit.jupiter.api.Assertions.assertTrue(pickup.isCancelled());
    }

    @Test
    void normalAndGlowFramesUseTheirOwnSettingsAndCorrectHand() {
        when(person.player().getInventory().getItem(EquipmentSlot.OFF_HAND)).thenReturn(mace);
        f.allowed.remove("item-frames");
        ItemFrame normal = mock(ItemFrame.class);
        when(normal.getType()).thenReturn(EntityType.ITEM_FRAME);
        var denied = mock(PlayerInteractEntityEvent.class);
        when(denied.getPlayer()).thenReturn(person.player());
        when(denied.getRightClicked()).thenReturn(normal);
        when(denied.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        listener.onFrame(denied);
        verify(denied).setCancelled(true);
        ItemFrame glow = mock(ItemFrame.class);
        when(glow.getType()).thenReturn(EntityType.GLOW_ITEM_FRAME);
        var allowed = mock(PlayerInteractEntityEvent.class);
        when(allowed.getPlayer()).thenReturn(person.player());
        when(allowed.getRightClicked()).thenReturn(glow);
        when(allowed.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        listener.onFrame(allowed);
        verify(allowed, never()).setCancelled(true);
    }

    @Test
    void poweredShelfChecksNonHeldHotbarSlotsAndStillAllowsEmptyRetrieval() {
        Material shelf = mock(Material.class);
        when(shelf.name()).thenReturn("OAK_SHELF");
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(shelf);
        BlockData powered = mock(BlockData.class, withSettings().extraInterfaces(Powerable.class));
        when(((Powerable) powered).isPowered()).thenReturn(true);
        when(block.getBlockData()).thenReturn(powered);
        var event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(person.player());
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        person.inventory().slots()[8] = mace;
        f.allowed.remove("shelves");
        listener.onPot(event);
        verify(event).setCancelled(true);
        clearInvocations(event);
        f.allowed.add("shelves");
        listener.onPot(event);
        verify(event, never()).setCancelled(true);
        f.allowed.remove("shelves");
        when(((Powerable) powered).isPowered()).thenReturn(false);
        listener.onPot(event); // Empty hand on an unpowered shelf can retrieve its item.
        verify(event, never()).setCancelled(true);
    }

    @Test
    void bundleInsertionUsesItsOwnRule() {
        ItemStack bundle = mock(ItemStack.class);
        when(bundle.getType()).thenReturn(Material.BUNDLE);
        var event = click(InventoryAction.PICKUP_ALL, person.inventory().inventory());
        when(event.getCursor()).thenReturn(bundle);
        when(event.getCurrentItem()).thenReturn(mace);
        listener.onClick(event);
        verify(event).setCancelled(true);
        clearInvocations(event);
        f.allowed.add("bundles");
        listener.onClick(event);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void allowedBundleCannotBypassItsBlockedOuterInventory() {
        f.allowed.add("bundles");
        ItemStack bundle = mock(ItemStack.class);
        when(bundle.getType()).thenReturn(Material.BUNDLE);
        var deposit = click(InventoryAction.PICKUP_ALL, chest.inventory());
        when(deposit.getCurrentItem()).thenReturn(bundle);
        when(deposit.getCursor()).thenReturn(mace);
        listener.onClick(deposit);
        verify(deposit).setCancelled(true);
        var retrieve = click(InventoryAction.PICKUP_ALL, chest.inventory());
        when(retrieve.getCursor()).thenReturn(bundle);
        when(retrieve.getCurrentItem()).thenReturn(mace);
        listener.onClick(retrieve);
        verify(retrieve, never()).setCancelled(true);
    }

    private InventoryClickEvent click(InventoryAction action, Inventory clicked) {
        var event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(person.player());
        when(event.getView()).thenReturn(person.view());
        when(event.getClickedInventory()).thenReturn(clicked);
        when(event.getAction()).thenReturn(action);
        when(event.getHotbarButton()).thenReturn(-1);
        return event;
    }
}
