package com.macemanager.limit;

import com.macemanager.config.PluginConfig;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.Inventory;

public final class StorageRules {
    private StorageRules() {
    }

    static Inventory inventory(org.bukkit.inventory.InventoryHolder holder) {
        // A live combined chest lookup can resolve a neighbour across a chunk boundary.
        return holder instanceof org.bukkit.block.Chest chest ? chest.getBlockInventory() : holder.getInventory();
    }

    public static boolean blocksMace(Inventory inventory, PluginConfig settings) {
        if (inventory == null) {
            return false;
        }
        // Name matching keeps newer destinations (notably shelves) optional on 1.21.4.
        return switch (inventory.getType().name()) {
            case "PLAYER", "CREATIVE", "GRINDSTONE" -> false;
            case "ANVIL", "ENCHANTING" -> !settings.allowMaceEnchanting();
            default -> !settings.storageAllowed(destination(inventory.getType().name()));
        };
    }

    public static String destination(String type) {
        return switch (type) {
            case "ENDER_CHEST" -> "ender-chests";
            case "SHELF" -> "shelves";
            case "CHEST" -> "chests";
            case "BARREL" -> "barrels";
            case "SHULKER_BOX" -> "shulker-boxes";
            case "HOPPER" -> "hoppers";
            case "DROPPER" -> "droppers";
            case "DISPENSER" -> "dispensers";
            case "DECORATED_POT" -> "decorated-pots";
            case "CRAFTER" -> "crafters";
            default -> "unsupported";
        };
    }

    public static boolean blocksMace(Entity entity, PluginConfig settings) {
        if (entity instanceof ItemFrame) {
            return !settings.storageAllowed(entity.getType().name().equals("GLOW_ITEM_FRAME")
                    ? "glow-item-frames" : "item-frames");
        }
        return entity instanceof ArmorStand && !settings.storageAllowed("armor-stands");
    }
}
