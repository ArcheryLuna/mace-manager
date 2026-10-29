package com.macemanager.limit;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Powerable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShelfSupportTest {
    private final StorageTestSupport f = new StorageTestSupport();
    private final StorageTestSupport.Person player = f.player("Player");
    private final ContainerListener listener = new ContainerListener(f.plugin);

    @Test
    void singlePoweredShelfOnlyChecksRightmostThreeSlotsEvenIfHoldingAMace() {
        var block = shelf(0, 0, BlockFace.NORTH, "unconnected");
        var mace = f.mace(UUID.randomUUID(), player.player().getUniqueId());
        player.inventory().slots()[0] = mace;
        when(player.player().getInventory().getItemInMainHand()).thenReturn(mace);
        var event = interact(block);
        when(event.getItem()).thenReturn(mace);
        f.allowed.remove("shelves");
        listener.onPot(event);
        verify(event, never()).setCancelled(true);
        player.inventory().slots()[6] = mace;
        listener.onPot(event);
        verify(event).setCancelled(true);
    }

    @Test
    void chainsSelectSixOrNineSlotsFromEitherEndInEveryOrientation() {
        for (BlockFace facing : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            int dx = -facing.getModZ();
            int dz = facing.getModX();
            Block left = shelf(0, 0, facing, "left");
            Block right = shelf(dx, dz, facing, "right");
            assertEquals(3, ShelfSupport.firstHotbarSlot(left));
            assertEquals(3, ShelfSupport.firstHotbarSlot(right));
            Block center = shelf(dx, dz, facing, "center");
            right = shelf(dx * 2, dz * 2, facing, "right");
            assertEquals(0, ShelfSupport.firstHotbarSlot(left));
            assertEquals(0, ShelfSupport.firstHotbarSlot(center));
            assertEquals(0, ShelfSupport.firstHotbarSlot(right));
            f.blocks.clear();
        }
    }

    @Test
    void unknownStateAndUnloadedNeighbourProtectHotbarWithoutLoadingChunks() {
        Block unknown = shelf(0, 0, BlockFace.NORTH, "future_value");
        assertEquals(0, ShelfSupport.firstHotbarSlot(unknown));
        Block edge = shelf(15, 0, BlockFace.NORTH, "left");
        when(f.world.isChunkLoaded(1, 0)).thenReturn(false);
        assertEquals(0, ShelfSupport.firstHotbarSlot(edge));
        verify(f.world, never()).getBlockAt(16, 64, 0);
        verify(f.world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test
    void unpoweredShelfChecksMainHandEvenDuringOffhandEvent() {
        Block block = shelf(0, 0, BlockFace.NORTH, "unconnected");
        when(((Powerable) block.getBlockData()).isPowered()).thenReturn(false);
        var event = interact(block);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        var mace = f.mace(UUID.randomUUID(), player.player().getUniqueId());
        when(player.player().getInventory().getItemInMainHand()).thenReturn(mace);
        f.allowed.remove("shelves");
        listener.onPot(event);
        verify(event).setCancelled(true);
        clearInvocations(event);
        when(player.player().getInventory().getItemInMainHand()).thenReturn(null);
        listener.onPot(event);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void onlyFrontFaceAndAllowedBlockUseAreCheckedRegardlessOfItemUseDenial() {
        Block block = shelf(0, 0, BlockFace.NORTH, "unconnected");
        var event = interact(block);
        player.inventory().slots()[8] = f.mace(UUID.randomUUID(), player.player().getUniqueId());
        f.allowed.remove("shelves");
        when(event.getBlockFace()).thenReturn(BlockFace.SOUTH);
        listener.onPot(event);
        verify(event, never()).setCancelled(true);
        when(event.getBlockFace()).thenReturn(BlockFace.NORTH);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DENY);
        listener.onPot(event);
        verify(event, never()).setCancelled(true);
        when(event.useInteractedBlock()).thenReturn(Event.Result.ALLOW);
        when(event.useItemInHand()).thenReturn(Event.Result.DENY);
        listener.onPot(event);
        verify(event).setCancelled(true);
    }

    @Test
    void poweredInteractionQueuesNeighbourChunksButNeverLoadsThem() {
        Block block = shelf(15, 0, BlockFace.NORTH, "unconnected");
        f.chunk(0, 0, new org.bukkit.block.BlockState[0]);
        f.chunk(1, 0, new org.bukkit.block.BlockState[0]);
        when(f.world.isChunkLoaded(0, -1)).thenReturn(false);
        listener.afterBlockInteract(interact(block));
        verify(f.world, atLeastOnce()).getChunkAt(1, 0);
        verify(f.world, never()).getChunkAt(0, -1);
    }

    @Test
    void newerShelfInventoryUsesSameRuleWithoutRequiringItsEnumOnOlderApi() {
        InventoryType shelfType;
        try {
            shelfType = InventoryType.valueOf("SHELF");
        } catch (IllegalArgumentException absentOnOldApi) {
            shelfType = mock(InventoryType.class);
            when(shelfType.name()).thenReturn("SHELF");
        }
        var inventory = f.inventory(shelfType, 3, f.at(0, 64, 0)).inventory();
        assertFalse(StorageRules.blocksMace(inventory, f.settings));
        f.allowed.remove("shelves");
        assertTrue(StorageRules.blocksMace(inventory, f.settings));
    }

    @Test
    void nativeShelfApiFitsTheBaselineInventoryAndBlockDataInterfacesWhenAvailable() throws Exception {
        try {
            Class<?> shelf = Class.forName("org.bukkit.block.Shelf");
            assertTrue(org.bukkit.inventory.InventoryHolder.class.isAssignableFrom(shelf));
            Class<?> data = Class.forName("org.bukkit.block.data.type.Shelf");
            assertTrue(Powerable.class.isAssignableFrom(data));
            assertTrue(Directional.class.isAssignableFrom(data));
            assertEquals(3, InventoryType.valueOf("SHELF").getDefaultSize());
        } catch (ClassNotFoundException absentOnOldApi) {
            assertThrows(IllegalArgumentException.class, () -> InventoryType.valueOf("SHELF"));
        }
    }

    private Block shelf(int x, int z, BlockFace facing, String chain) {
        Material material = mock(Material.class);
        when(material.name()).thenReturn("OAK_SHELF");
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.getWorld()).thenReturn(f.world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(z);
        when(block.getLocation()).thenReturn(f.at(x, 64, z));
        BlockData data = mock(BlockData.class, withSettings().extraInterfaces(Powerable.class, Directional.class));
        when(((Powerable) data).isPowered()).thenReturn(true);
        when(((Directional) data).getFacing()).thenReturn(facing);
        when(data.getAsString()).thenReturn("minecraft:oak_shelf[facing=north,powered=true,side_chain=" + chain + ",waterlogged=false]");
        when(block.getBlockData()).thenReturn(data);
        f.blocks.put(List.of(x, 64, z), block);
        return block;
    }

    private PlayerInteractEvent interact(Block block) {
        var event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player.player());
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getBlockFace()).thenReturn(BlockFace.NORTH);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        return event;
    }
}
