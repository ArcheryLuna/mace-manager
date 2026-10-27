package com.macemanager.limit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class BundleContents {
    private BundleContents() {
    }

    static List<ItemStack> extractMaces(ItemStack bundle, Predicate<ItemStack> match) {
        if (!(bundle.getItemMeta() instanceof BundleMeta meta)) {
            return List.of();
        }
        List<ItemStack> kept = new ArrayList<>();
        List<ItemStack> removed = new ArrayList<>();
        for (ItemStack content : meta.getItems()) {
            if (content == null || content.getType().isAir()) {
                continue;
            }
            if (content.getType() == Material.MACE && match.test(content)) {
                removed.add(content.clone());
            } else {
                kept.add(content);
            }
        }
        if (!removed.isEmpty()) {
            meta.setItems(kept);
            bundle.setItemMeta(meta);
        }
        return removed;
    }
}
