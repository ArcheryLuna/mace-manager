package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.text.Notices;
import org.bukkit.Chunk;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.block.data.Powerable;
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean intoSlotBundle = Bundles.isBundle(event.getCurrentItem()) && NestedMaces.contains(event.getCursor());
        boolean intoCursorBundle = Bundles.isBundle(event.getCursor()) && NestedMaces.contains(event.getCurrentItem());
        if ((intoSlotBundle || intoCursorBundle)
                && (!plugin.settings().storageAllowed("bundles")
                        || (intoSlotBundle && blocked(event.getClickedInventory())))) {
            deny(event, player);
            return;
        }
        if (!wouldStoreMace(event)) {
            return;
        }
        deny(event, player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        ItemStack dragged = event.getOldCursor();
        if (!NestedMaces.contains(dragged)) {
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (NestedMaces.contains(event.getItem()) && blocked(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (NestedMaces.contains(event.getItem().getItemStack()) && blocked(event.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (NestedMaces.contains(event.getItem())
                && event.getBlock().getState() instanceof org.bukkit.inventory.InventoryHolder holder
                && blocked(holder.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrame(PlayerInteractEntityEvent event) {
        ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand());
        if (!NestedMaces.contains(hand)) {
            return;
        }
        if (StorageRules.blocksMace(event.getRightClicked(), plugin.settings())) {
            event.setCancelled(true);
            Notices.send(event.getPlayer(), plugin.settings().format("containers-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (!NestedMaces.contains(event.getPlayerItem())
                || !StorageRules.blocksMace(event.getRightClicked(), plugin.settings())) {
            return;
        }
        event.setCancelled(true);
        Notices.send(event.getPlayer(), plugin.settings().format("containers-blocked"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPot(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        String type = event.getClickedBlock().getType().name();
        boolean shelf = type.endsWith("_SHELF");
        if (!shelf && !type.equals("DECORATED_POT")) {
            return;
        }
        boolean incoming = NestedMaces.contains(event.getItem());
        // Powered shelves can exchange the hotbar, including slots outside the held hand.
        if (shelf && event.getClickedBlock().getBlockData() instanceof Powerable power && power.isPowered()) {
            for (int slot = 0; slot < 9; slot++) {
                incoming |= NestedMaces.contains(event.getPlayer().getInventory().getItem(slot));
            }
        }
        if (incoming && !plugin.settings().storageAllowed(shelf ? "shelves" : "decorated-pots")) {
            event.setCancelled(true);
            Notices.send(event.getPlayer(), plugin.settings().format("containers-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityAt(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        onFrame(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterMove(InventoryMoveItemEvent event) {
        if (NestedMaces.contains(event.getItem())) {
            plugin.tracking().touch(event.getItem());
            plugin.tracking().queueInventory(event.getSource());
            plugin.tracking().queueInventory(event.getDestination());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterPickup(InventoryPickupItemEvent event) {
        if (NestedMaces.contains(event.getItem().getItemStack())) {
            plugin.tracking().touch(event.getItem().getItemStack());
            plugin.tracking().queueInventory(event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterDispense(BlockDispenseEvent event) {
        if (NestedMaces.contains(event.getItem())) {
            plugin.tracking().touch(event.getItem());
            plugin.tracking().queueLocation(event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterBlockInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null) {
            String type = event.getClickedBlock().getType().name();
            if (type.endsWith("_SHELF") || type.equals("DECORATED_POT")) {
                for (int slot = 0; slot < 9; slot++) {
                    plugin.tracking().touch(event.getPlayer().getInventory().getItem(slot));
                }
                plugin.tracking().touch(event.getItem());
                plugin.tracking().markDirty(event.getPlayer());
                plugin.tracking().queueLocation(event.getClickedBlock().getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame || event.getRightClicked() instanceof ArmorStand) {
            plugin.tracking().touch(event.getPlayer().getInventory().getItem(event.getHand()));
            plugin.tracking().markDirty(event.getPlayer());
            plugin.tracking().queueLocation(event.getRightClicked().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterEntityAt(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
        afterEntityInteract(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterStand(PlayerArmorStandManipulateEvent event) {
        plugin.tracking().touch(event.getPlayerItem());
        plugin.tracking().touch(event.getArmorStandItem());
        plugin.tracking().markDirty(event.getPlayer());
        plugin.tracking().queueLocation(event.getRightClicked().getLocation());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        strip(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent event) {
        plugin.tracking().beforeUnload(event.getChunk());
    }

    public void strip(Chunk chunk) {
        plugin.tracking().queueChunk(chunk);
    }

    private void deny(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        Notices.send(player, plugin.settings().format("containers-blocked"));
    }

    private boolean wouldStoreMace(InventoryClickEvent event) {
        Inventory clicked = event.getClickedInventory();
        InventoryAction action = event.getAction();
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (!NestedMaces.contains(event.getCurrentItem()) || clicked == null) {
                return false;
            }
            Inventory destination = clicked.equals(event.getView().getTopInventory())
                    ? event.getView().getBottomInventory() : event.getView().getTopInventory();
            return blocked(destination);
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            if (!blocked(clicked) || !(event.getWhoClicked() instanceof Player player)) {
                return false;
            }
            return NestedMaces.contains(player.getInventory().getItemInOffHand());
        }
        if (action == InventoryAction.HOTBAR_SWAP || "HOTBAR_MOVE_AND_READD".equals(action.name())) {
            if (!blocked(clicked) || !(event.getWhoClicked() instanceof Player player)) {
                return false;
            }
            int hotbar = event.getHotbarButton();
            ItemStack swapped = hotbar >= 0 ? player.getInventory().getItem(hotbar) : null;
            return NestedMaces.contains(swapped);
        }
        if (action == InventoryAction.PLACE_ALL
                || action == InventoryAction.PLACE_ONE
                || action == InventoryAction.PLACE_SOME
                || action == InventoryAction.SWAP_WITH_CURSOR) {
            return blocked(clicked) && NestedMaces.contains(event.getCursor());
        }
        return false;
    }

    private boolean blocked(Inventory inventory) {
        return StorageRules.blocksMace(inventory, plugin.settings());
    }

    private Inventory inventoryAt(InventoryView view, int rawSlot) {
        Inventory top = view.getTopInventory();
        if (rawSlot >= 0 && rawSlot < top.getSize()) {
            return top;
        }
        return view.getBottomInventory();
    }
}
