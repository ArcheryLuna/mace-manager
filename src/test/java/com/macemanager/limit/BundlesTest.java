package com.macemanager.limit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BundlesTest {
    @Test
    void recognizesUndyedAndEveryDyedBundleForStorageChecksAndCleanup() {
        String[] colors = {
                "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
                "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"
        };
        assertTrue(Bundles.isBundle(stack(Material.BUNDLE)));
        for (String color : colors) {
            Material material = Material.valueOf(color + "_BUNDLE");
            assertTrue(Bundles.isBundle(stack(material)), material.name());
        }
        assertFalse(Bundles.isBundle(null));
        assertFalse(Bundles.isBundle(stack(Material.MACE)));
        assertFalse(Bundles.isBundle(stack(Material.RED_SHULKER_BOX)));
    }

    @SuppressWarnings("deprecation")
    private ItemStack stack(Material material) {
        // Avoid the server item factory; classification only needs the material.
        return new ItemStack() {
            @Override
            public Material getType() {
                return material;
            }
        };
    }
}
