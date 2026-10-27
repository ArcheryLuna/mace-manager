package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.config.PluginConfig;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.mockito.Mockito.*;

class CraftingListenerTest {
    private final MaceManagerPlugin plugin = mock(MaceManagerPlugin.class);
    private final PluginConfig settings = mock(PluginConfig.class);
    private final MaceRegistry registry = mock(MaceRegistry.class);
    private final MaceItems items = mock(MaceItems.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final CraftingInventory crafting = mock(CraftingInventory.class);
    private final CraftItemEvent event = mock(CraftItemEvent.class);
    private final ItemStack ingredient = mock(ItemStack.class);
    private final CraftingListener listener = new CraftingListener(plugin);

    @BeforeEach
    void setUp() {
        when(plugin.settings()).thenReturn(settings);
        when(plugin.registry()).thenReturn(registry);
        when(plugin.maceItems()).thenReturn(items);
        when(plugin.recipes()).thenReturn(mock(RecipeService.class));
        when(plugin.announcements()).thenReturn(mock(MaceAnnouncements.class));
        when(settings.allowedMaces()).thenReturn(3);
        when(settings.hasMaceLimit()).thenReturn(true);
        when(settings.format("limit-reached")).thenReturn("Limit reached");
        when(player.getName()).thenReturn("Crafter");
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getInventory()).thenReturn(crafting);
        when(event.isShiftClick()).thenReturn(true);
        Recipe recipe = mock(Recipe.class);
        ItemStack result = mock(ItemStack.class);
        when(result.getType()).thenReturn(Material.MACE);
        when(result.getAmount()).thenReturn(1);
        when(recipe.getResult()).thenReturn(result);
        when(event.getRecipe()).thenReturn(recipe);
        // Material.isAir() needs live registries in newer Paper APIs.
        // This fixture only needs a non-air ingredient, not a block registry.
        when(ingredient.getType()).thenReturn(mock(Material.class));
        when(ingredient.getAmount()).thenReturn(8);
        when(crafting.getMatrix()).thenReturn(new ItemStack[]{ingredient});
    }

    @Test
    void shiftCraftOnlyCreatesRemainingCapacityWithSeparateIdentities() {
        when(registry.count()).thenReturn(1);
        ItemStack first = mock(ItemStack.class);
        ItemStack second = mock(ItemStack.class);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(items.createTracked()).thenReturn(
                new MaceItems.TaggedMace(firstId, first), new MaceItems.TaggedMace(secondId, second));
        when(inventory.addItem(first)).thenReturn(new HashMap<>());
        when(inventory.addItem(second)).thenReturn(new HashMap<>());

        listener.onCraft(event);

        verify(event).setCancelled(true);
        verify(items, times(2)).createTracked();
        verify(registry).register(firstId, player);
        verify(registry).register(secondId, player);
        verify(inventory).addItem(first);
        verify(inventory).addItem(second);
        verify(ingredient).setAmount(6);
        verify(crafting).setMatrix(argThat(matrix -> matrix.length == 1 && matrix[0] == ingredient));
        verify(plugin.recipes()).sync();
    }

    @Test
    void fullLimitCancelsWithoutConsumingIngredientsOrCreatingItems() {
        when(registry.count()).thenReturn(3);
        listener.onCraft(event);
        verify(event).setCancelled(true);
        verify(crafting).setResult(null);
        verify(crafting, never()).setMatrix(any());
        verifyNoInteractions(items);
        verify(registry, never()).register(any(), any());
    }

    @Test
    void fullInventoryPreventsShiftCraftWithoutConsumingIngredients() {
        ItemStack occupied = mock(ItemStack.class);
        when(occupied.getType()).thenReturn(mock(Material.class));
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{occupied});
        when(inventory.getItemInOffHand()).thenReturn(occupied);
        listener.onCraft(event);
        verify(event).setCancelled(true);
        verify(crafting, never()).setMatrix(any());
        verifyNoInteractions(items);
    }
}
