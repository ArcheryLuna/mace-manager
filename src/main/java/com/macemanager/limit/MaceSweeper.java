package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.function.Predicate;

public final class MaceSweeper {
    private static final EquipmentSlot[] ARMOR_STAND_SLOTS = {
            EquipmentSlot.HAND,
            EquipmentSlot.OFF_HAND,
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };

    private final MaceManagerPlugin plugin;

    public MaceSweeper(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    public void remove(Predicate<ItemStack> match, java.util.function.IntConsumer complete, Runnable failed) {
        int[] removed = {0};
        ScanQueue.Job job = plugin.scans().submit(() -> complete.accept(removed[0]), exception -> failed.run());
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            job.add(() -> {
                if (player.isOnline()) {
                    removed[0] += removeFromPlayer(player, match);
                }
            });
        }
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                job.add(() -> queueChunk(chunk, match, removed, job));
            }
        }
    }

    private int removeFromPlayer(Player player, Predicate<ItemStack> match) {
        int removed = 0;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            removed += clearSlot(inventory, slot, match);
        }
        ItemStack cursor = player.getItemOnCursor();
        int nested = clearNested(cursor, match);
        removed += nested;
        if (nested > 0) {
            player.setItemOnCursor(cursor);
        }
        if (cursor != null && match.test(cursor)) {
            player.setItemOnCursor(null);
            removed++;
        }
        removed += clearInventory(player.getEnderChest(), match);
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top != null && top != inventory) {
            removed += clearInventory(top, match);
        }
        return removed;
    }

    private void queueChunk(Chunk chunk, Predicate<ItemStack> match, int[] removed, ScanQueue.Job job) {
        if (!chunk.isLoaded()) {
            return;
        }
        for (org.bukkit.block.BlockState state : plugin.chunkAccess().tiles(chunk)) {
            if (state instanceof Container) {
                org.bukkit.block.Block block = state.getBlock();
                job.add(() -> {
                    if (chunk.isLoaded() && plugin.chunkAccess().state(block) instanceof Container container) {
                        removed[0] += clearInventory(container.getInventory(), match);
                    }
                });
            }
        }
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            job.add(() -> {
                if (entity.isValid()) {
                    removed[0] += removeFromEntity(entity, match);
                }
            });
        }
    }

    private int removeFromEntity(org.bukkit.entity.Entity entity, Predicate<ItemStack> match) {
        int removed = 0;
        if (entity instanceof Item item) {
            ItemStack stack = item.getItemStack();
            if (match.test(stack)) {
                item.remove();
                removed++;
            } else {
                int nested = clearNested(stack, match);
                if (nested > 0) {
                    item.setItemStack(stack);
                    removed += nested;
                }
            }
        } else if (entity instanceof ItemFrame frame && match.test(frame.getItem())) {
            frame.setItem(new ItemStack(Material.AIR));
            removed++;
        } else if (entity instanceof ArmorStand stand) {
            for (EquipmentSlot slot : ARMOR_STAND_SLOTS) {
                if (match.test(stand.getItem(slot))) {
                    stand.setItem(slot, null);
                    removed++;
                }
            }
        }
        return removed;
    }

    private int clearInventory(Inventory inventory, Predicate<ItemStack> match) {
        if (inventory == null) {
            return 0;
        }
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            removed += clearSlot(inventory, slot, match);
        }
        return removed;
    }

    private int clearSlot(Inventory inventory, int slot, Predicate<ItemStack> match) {
        ItemStack stack = inventory.getItem(slot);
        if (stack != null && match.test(stack)) {
            inventory.setItem(slot, null);
            return 1;
        }
        int removed = clearNested(stack, match);
        if (removed > 0) {
            inventory.setItem(slot, stack);
        }
        return removed;
    }

    private int clearNested(ItemStack stack, Predicate<ItemStack> match) {
        if (Bundles.isBundle(stack)) {
            return Bundles.extractMaces(stack, match).size();
        }
        return ShulkerMaces.extractMaces(stack, match).size();
    }
}
