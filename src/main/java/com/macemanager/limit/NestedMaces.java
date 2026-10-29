package com.macemanager.limit;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/** Reads/writes portable storage without extracting allowed maces during an audit. */
final class NestedMaces {
    private NestedMaces() {
    }

    static String destination(ItemStack item) {
        if (Bundles.isBundle(item)) {
            return "bundles";
        }
        return item != null && item.getType().name().endsWith("SHULKER_BOX") ? "shulker-boxes" : null;
    }

    static List<ItemStack> contents(ItemStack item) {
        String type = destination(item);
        if ("bundles".equals(type) && item.getItemMeta() instanceof BundleMeta meta) {
            return new ArrayList<>(meta.getItems());
        }
        if ("shulker-boxes".equals(type) && item.getItemMeta() instanceof BlockStateMeta meta
                && meta.getBlockState() instanceof ShulkerBox box) {
            return new ArrayList<>(Arrays.asList(box.getInventory().getContents()));
        }
        return List.of();
    }

    static void contents(ItemStack item, List<ItemStack> contents) {
        if (Bundles.isBundle(item) && item.getItemMeta() instanceof BundleMeta meta) {
            meta.setItems(contents.stream().filter(stack -> stack != null && stack.getType() != Material.AIR).toList());
            item.setItemMeta(meta);
        } else if (item.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box) {
            box.getInventory().setContents(contents.toArray(ItemStack[]::new));
            meta.setBlockState(box);
            item.setItemMeta(meta);
        }
    }

    static boolean contains(ItemStack item) {
        return contains(item, stack -> true);
    }

    static boolean contains(ItemStack item, Predicate<ItemStack> match) {
        if (item == null) {
            return false;
        }
        if (item.getType() == Material.MACE) {
            return match.test(item);
        }
        for (ItemStack child : contents(item)) {
            if (contains(child, match)) {
                return true;
            }
        }
        return false;
    }
}
