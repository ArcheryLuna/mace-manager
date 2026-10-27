package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.text.Notices;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

public final class ContainerListener implements Listener {
    private final MaceManagerPlugin plugin;

    public ContainerListener(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!enforcing() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (pairsMaceWithBundle(event.getCursor(), event.getCurrentItem())) {
            deny(event, player);
            return;
        }
        if (!wouldStoreMace(event)) {
            return;
        }
        deny(event, player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!enforcing()) {
            return;
        }
        ItemStack dragged = event.getOldCursor();
        if (!plugin.maceItems().isMace(dragged)) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (blocked(inventoryAt(event.getView(), rawSlot))) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    Notices.send(player, plugin.settings().format("containers-blocked"));
                }
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (enforcing() && plugin.maceItems().isMace(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (enforcing() && plugin.maceItems().isMace(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (enforcing() && plugin.maceItems().isMace(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrame(PlayerInteractEntityEvent event) {
        ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand());
        if (!enforcing() || !plugin.maceItems().isMace(hand)) {
            return;
        }
        if (event.getRightClicked() instanceof ItemFrame || event.getRightClicked() instanceof ArmorStand) {
            event.setCancelled(true);
            Notices.send(event.getPlayer(), plugin.settings().format("containers-blocked"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!enforcing() || !plugin.maceItems().isMace(event.getPlayerItem())) {
            return;
        }
        event.setCancelled(true);
        Notices.send(event.getPlayer(), plugin.settings().format("containers-blocked"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPot(PlayerInteractEvent event) {
        if (!enforcing() || event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.DECORATED_POT) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !plugin.maceItems().isMace(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Notices.send(event.getPlayer(), plugin.settings().format("containers-blocked"));
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (enforcing()) {
            strip(event.getChunk());
        }
    }

    public void strip(Chunk chunk) {
        plugin.tracking().queueChunk(chunk);
    }

    private boolean enforcing() {
        return plugin.settings().hasMaceLimit();
    }

    private void deny(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        Notices.send(player, plugin.settings().format("containers-blocked"));
    }

    private boolean wouldStoreMace(InventoryClickEvent event) {
        MaceItems items = plugin.maceItems();
        Inventory clicked = event.getClickedInventory();
        InventoryAction action = event.getAction();
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (!items.isMace(event.getCurrentItem()) || clicked == null || blocked(clicked)) {
                return false;
            }
            return blocked(event.getView().getTopInventory());
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            if (!blocked(clicked) || !(event.getWhoClicked() instanceof Player player)) {
                return false;
            }
            return items.isMace(player.getInventory().getItemInOffHand());
        }
        if (action == InventoryAction.HOTBAR_SWAP || "HOTBAR_MOVE_AND_READD".equals(action.name())) {
            if (!blocked(clicked) || !(event.getWhoClicked() instanceof Player player)) {
                return false;
            }
            int hotbar = event.getHotbarButton();
            ItemStack swapped = hotbar >= 0 ? player.getInventory().getItem(hotbar) : null;
            return items.isMace(swapped);
        }
        if (action == InventoryAction.PLACE_ALL
                || action == InventoryAction.PLACE_ONE
                || action == InventoryAction.PLACE_SOME
                || action == InventoryAction.SWAP_WITH_CURSOR) {
            return blocked(clicked) && items.isMace(event.getCursor());
        }
        return false;
    }

    private boolean pairsMaceWithBundle(ItemStack first, ItemStack second) {
        return (plugin.maceItems().isMace(first) && Bundles.isBundle(second))
                || (Bundles.isBundle(first) && plugin.maceItems().isMace(second));
    }

    private boolean blocked(Inventory inventory) {
        return StorageRules.blocksMace(inventory, plugin.settings().allowMaceEnchanting());
    }

    private Inventory inventoryAt(InventoryView view, int rawSlot) {
        Inventory top = view.getTopInventory();
        if (rawSlot >= 0 && rawSlot < top.getSize()) {
            return top;
        }
        return view.getBottomInventory();
    }
}
