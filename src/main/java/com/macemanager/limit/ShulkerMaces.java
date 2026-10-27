package com.macemanager.limit;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class ShulkerMaces {
    private ShulkerMaces() {
    }

    public static boolean isShulker(ItemStack stack) {
        return stack != null && Tag.SHULKER_BOXES.isTagged(stack.getType());
    }

    public static List<ItemStack> extractMaces(ItemStack shulker) {
        return extractMaces(shulker, stack -> true);
    }

    static List<ItemStack> extractMaces(ItemStack shulker, Predicate<ItemStack> match) {
        if (!isShulker(shulker) || !(shulker.getItemMeta() instanceof BlockStateMeta meta)) {
            return List.of();
        }
        if (!(meta.getBlockState() instanceof ShulkerBox box)) {
            return List.of();
        }
        Inventory inventory = box.getInventory();
        List<ItemStack> removed = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack content = inventory.getItem(slot);
            if (content != null && content.getType() == Material.MACE && match.test(content)) {
                removed.add(content.clone());
                inventory.setItem(slot, null);
            }
        }
        if (!removed.isEmpty()) {
            meta.setBlockState(box);
            shulker.setItemMeta(meta);
        }
        return removed;
    }
}
