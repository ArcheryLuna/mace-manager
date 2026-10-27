package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.config.PluginConfig;
import org.bukkit.Server;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrackingListenerTest {
    private final MaceManagerPlugin plugin = mock(MaceManagerPlugin.class);
    private final MaceRegistry registry = mock(MaceRegistry.class);
    private final MaceItems items = mock(MaceItems.class);
    private final Item item = mock(Item.class);
    private final Player player = mock(Player.class);
    private final UUID id = UUID.randomUUID();
    private final TrackingListener listener = new TrackingListener(plugin);

    @BeforeEach
    void setUp() {
        when(plugin.registry()).thenReturn(registry);
        when(plugin.maceItems()).thenReturn(items);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        ItemStack stack = mock(ItemStack.class);
        when(item.getItemStack()).thenReturn(stack);
        when(items.classify(stack)).thenReturn(new MaceItems.Classification(true, false, id));
    }

    @Test
    void registeredPickupTransfersOwnershipWithoutRemovingItem() {
        when(registry.contains(id)).thenReturn(true);
        EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
        listener.onPickup(event);
        assertFalse(event.isCancelled());
        verify(registry).hold(id, player);
        verify(item, never()).remove();
    }

    @Test
    void unknownIdCannotBePickedUpOrRegisteredAsLegitimate() {
        PluginConfig settings = mock(PluginConfig.class);
        when(plugin.settings()).thenReturn(settings);
        when(settings.format("illegal-removed")).thenReturn("Illegal mace removed");
        EntityPickupItemEvent event = new EntityPickupItemEvent(player, item, 0);
        listener.onPickup(event);
        assertTrue(event.isCancelled());
        verify(item).remove();
        verify(registry, never()).hold(any(), any());
        verify(registry, never()).register(any(), any());
    }

    @Test
    void mobsCannotTakeTrackedMaces() {
        EntityPickupItemEvent event = new EntityPickupItemEvent(mock(Zombie.class), item, 0);
        listener.onPickup(event);
        assertTrue(event.isCancelled());
        verifyNoInteractions(registry);
        verify(item, never()).remove();
    }

    @Test
    void fullAuditPreservesOfflineOwnershipWhenItemIsNotLoaded() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getOnlinePlayers()).thenReturn(List.of());
        when(server.getWorlds()).thenReturn(List.of());
        MaceRecord record = new MaceRecord(id, player.getUniqueId(), "Offline");
        when(registry.all()).thenReturn(List.of(record));
        listener.reconcileAll();
        verify(plugin, never()).destroyMace(any());
        verify(registry, never()).remove(any());
    }
}
