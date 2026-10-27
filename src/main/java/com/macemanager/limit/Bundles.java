package com.macemanager.limit;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Predicate;

public final class Bundles {
    private static final boolean SUPPORTED = detect();

    private Bundles() {
    }

    public static boolean supported() {
        return SUPPORTED;
    }

    public static boolean isBundle(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        String type = stack.getType().name();
        return "BUNDLE".equals(type) || type.endsWith("_BUNDLE");
    }

    public static List<ItemStack> extractMaces(ItemStack bundle) {
        return extractMaces(bundle, stack -> true);
    }

    static List<ItemStack> extractMaces(ItemStack bundle, Predicate<ItemStack> match) {
        if (!SUPPORTED || !isBundle(bundle)) {
            return List.of();
        }
        return BundleContents.extractMaces(bundle, match);
    }

    private static boolean detect() {
        try {
            Class.forName("org.bukkit.inventory.meta.BundleMeta");
            return true;
        } catch (ClassNotFoundException exception) {
            return false;
        }
    }
}
