package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.text.Notices;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PlayerMaceCleanup {
    private static final EquipmentSlot[] ARMOR_STAND_SLOTS = {
            EquipmentSlot.HAND,
            EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    private final MaceManagerPlugin plugin;
    private int illegalRemoved;

    public PlayerMaceCleanup(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    public void clean(Player player, Set<UUID> seen) {
        illegalRemoved = 0;
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> extracted = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            keepSlot(inventory, slot, player, seen, extracted);
        }
        // Give extracted maces back after the inventory scan so they aren't
        // visited a second time and mistaken for duplicate IDs.
        for (ItemStack nested : extracted) {
            accept(player, nested, seen);
        }
        keepCursor(player, seen);
        takeFrom(player.getEnderChest(), player, seen);
        takeFromOpenView(player, seen);
        if (illegalRemoved > 0) {
            Notices.send(player, plugin.settings().format("illegal-removed"));
        }
    }

    public void queueChunk(org.bukkit.Chunk chunk, ScanQueue.Job job) {
        if (!chunk.isLoaded()) {
            return;
        }
        for (org.bukkit.block.BlockState state : plugin.chunkAccess().tiles(chunk)) {
            if (state instanceof Container) {
                org.bukkit.block.Block block = state.getBlock();
                job.add(() -> {
                    if (!chunk.isLoaded()) {
                        return;
                    }
                    // Re-resolve on this tick: the block may have changed since enumeration.
                    if (plugin.chunkAccess().state(block) instanceof Container container) {
                        releaseInventory(container.getInventory(), block.getLocation());
                    }
                });
            }
        }
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            job.add(() -> {
                if (entity.isValid()) {
                    stripEntity(entity);
                }
            });
        }
    }

    private void stripEntity(org.bukkit.entity.Entity entity) {
        if (entity instanceof ItemFrame frame) {
            ItemStack displayed = frame.getItem();
            if (plugin.maceItems().isMace(displayed)) {
                frame.setItem(new ItemStack(Material.AIR));
                release(displayed.clone(), frame.getLocation());
            }
        } else if (entity instanceof ArmorStand stand) {
            for (EquipmentSlot slot : ARMOR_STAND_SLOTS) {
                ItemStack equipped = stand.getItem(slot);
                if (plugin.maceItems().isMace(equipped)) {
                    stand.setItem(slot, null);
                    release(equipped.clone(), stand.getLocation());
                }
            }
        }
    }

    private void keepSlot(PlayerInventory inventory, int slot, Player player, Set<UUID> seen, List<ItemStack> pending) {
        ItemStack stack = inventory.getItem(slot);
        if (stack == null) {
            return;
        }
        List<ItemStack> extracted = nestedMaces(stack);
        if (!extracted.isEmpty()) {
            inventory.setItem(slot, stack);
        }
        pending.addAll(extracted);
        MaceItems.Classification classification = plugin.maceItems().classify(stack);
        if (!classification.mace() || classification.untracked()) {
            return;
        }
        UUID id = classification.id();
        if (id == null || !plugin.registry().contains(id) || !seen.add(id)) {
            inventory.setItem(slot, null);
            illegalRemoved++;
            return;
        }
        plugin.registry().hold(id, player);
    }

    private void keepCursor(Player player, Set<UUID> seen) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return;
        }
        List<ItemStack> extracted = nestedMaces(cursor);
        if (!extracted.isEmpty()) {
            player.setItemOnCursor(cursor);
        }
        for (ItemStack nested : extracted) {
            accept(player, nested, seen);
        }
        MaceItems.Classification classification = plugin.maceItems().classify(cursor);
        if (!classification.mace() || classification.untracked()) {
            return;
        }
        UUID id = classification.id();
        if (id == null || !plugin.registry().contains(id) || !seen.add(id)) {
            player.setItemOnCursor(null);
            illegalRemoved++;
            return;
        }
        plugin.registry().hold(id, player);
    }

    private void takeFrom(Inventory inventory, Player player, Set<UUID> seen) {
        if (inventory == null) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null) {
                continue;
            }
            List<ItemStack> extracted = nestedMaces(stack);
            if (!extracted.isEmpty()) {
                inventory.setItem(slot, stack);
            }
            for (ItemStack nested : extracted) {
                accept(player, nested, seen);
            }
            if (!plugin.maceItems().isMace(stack)) {
                continue;
            }
            inventory.setItem(slot, null);
            accept(player, stack, seen);
        }
    }

    private void takeFromOpenView(Player player, Set<UUID> seen) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top instanceof org.bukkit.inventory.CraftingInventory crafting) {
            note(crafting.getResult(), player, seen);
            ItemStack[] matrix = crafting.getMatrix();
            boolean changed = false;
            for (int slot = 0; slot < matrix.length; slot++) {
                ItemStack stack = matrix[slot];
                if (stack == null) {
                    continue;
                }
                List<ItemStack> extracted = nestedMaces(stack);
                changed |= !extracted.isEmpty();
                for (ItemStack nested : extracted) {
                    accept(player, nested, seen);
                }
                if (!plugin.maceItems().isMace(stack)) {
                    continue;
                }
                matrix[slot] = null;
                changed = true;
                accept(player, stack, seen);
            }
            if (changed) {
                crafting.setMatrix(matrix);
            }
            return;
        }
        if (StorageRules.blocksMace(top, plugin.settings().allowMaceEnchanting())) {
            takeFrom(top, player, seen);
            return;
        }
        for (int slot = 0; slot < top.getSize(); slot++) {
            ItemStack stack = top.getItem(slot);
            if (stack == null) {
                continue;
            }
            List<ItemStack> extracted = nestedMaces(stack);
            if (!extracted.isEmpty()) {
                top.setItem(slot, stack);
            }
            for (ItemStack nested : extracted) {
                accept(player, nested, seen);
            }
            note(stack, player, seen);
        }
    }

    private void note(ItemStack stack, Player player, Set<UUID> seen) {
        UUID id = plugin.maceItems().trackedId(stack);
        if (id == null || !plugin.registry().contains(id)) {
            return;
        }
        if (seen.add(id)) {
            plugin.registry().hold(id, player);
        }
    }

    private void accept(Player player, ItemStack stack, Set<UUID> seen) {
        MaceItems items = plugin.maceItems();
        MaceItems.Classification classification = items.classify(stack);
        if (classification.untracked()) {
            give(player, stack);
            return;
        }
        UUID id = classification.id();
        if (id == null || !plugin.registry().contains(id) || seen.contains(id)) {
            illegalRemoved++;
            return;
        }
        var leftover = player.getInventory().addItem(stack);
        if (leftover.isEmpty()) {
            seen.add(id);
            plugin.registry().hold(id, player);
            return;
        }
        for (ItemStack left : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    private void give(Player player, ItemStack stack) {
        var leftover = player.getInventory().addItem(stack);
        for (ItemStack left : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    private void releaseInventory(Inventory inventory, Location location) {
        List<ItemStack> released = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null) {
                continue;
            }
            List<ItemStack> extracted = nestedMaces(stack);
            if (!extracted.isEmpty()) {
                inventory.setItem(slot, stack);
                released.addAll(extracted);
            }
            if (plugin.maceItems().isMace(stack)) {
                released.add(stack.clone());
                inventory.setItem(slot, null);
            }
        }
        for (ItemStack stack : released) {
            release(stack, location);
        }
    }

    private void release(ItemStack stack, Location location) {
        MaceItems items = plugin.maceItems();
        MaceItems.Classification classification = items.classify(stack);
        UUID id = classification.id();
        boolean keep = classification.untracked() || (id != null && plugin.registry().contains(id));
        if (!keep || location.getWorld() == null) {
            return;
        }
        location.getWorld().dropItemNaturally(location, stack);
    }

    private List<ItemStack> nestedMaces(ItemStack stack) {
        if (Bundles.isBundle(stack)) {
            return Bundles.extractMaces(stack);
        }
        return ShulkerMaces.extractMaces(stack);
    }
}
