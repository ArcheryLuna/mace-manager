package com.macemanager.limit;

import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

public final class StorageRules {
    private StorageRules() {
    }

    public static boolean blocksMace(Inventory inventory, boolean allowEnchanting) {
        if (inventory == null) {
            return false;
        }
        InventoryType type = inventory.getType();
        if (type == InventoryType.PLAYER || type == InventoryType.CREATIVE || type == InventoryType.GRINDSTONE) {
            return false;
        }
        if (type == InventoryType.ANVIL || type == InventoryType.ENCHANTING) {
            return !allowEnchanting;
        }
        return true;
    }
}
