package com.macemanager.limit;

import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Powerable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

/** Shelf compatibility using only APIs available in 1.21.4. */
final class ShelfSupport {
    private ShelfSupport() {
    }

    static boolean isShelf(Block block) {
        return block != null && block.getType().name().endsWith("_SHELF");
    }

    static boolean interacts(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.useInteractedBlock() == Event.Result.DENY || !isShelf(event.getClickedBlock())) {
            return false;
        }
        // Shelves only accept interaction on their front face.
        return !(event.getClickedBlock().getBlockData() instanceof Directional directional)
                || event.getBlockFace() == directional.getFacing();
    }

    static boolean powered(Block block) {
        return block.getBlockData() instanceof Powerable power && power.isPowered();
    }

    static int firstHotbarSlot(Block block) {
        BlockData data = block.getBlockData();
        String part = chain(data);
        if ("unconnected".equals(part)) {
            return 6;
        }
        if ("center".equals(part)) {
            return 0;
        }
        if (("left".equals(part) || "right".equals(part)) && data instanceof Directional directional) {
            // An end touches CENTER in a three-shelf chain or the opposite end in
            // a pair. Inspect both sides so left/right naming conventions cannot
            // make us miss a nine-slot swap. Do not cross unloaded boundaries.
            boolean pair = false;
            for (int sign : new int[]{-1, 1}) {
                int x = block.getX() - directional.getFacing().getModZ() * sign;
                int z = block.getZ() + directional.getFacing().getModX() * sign;
                if (!block.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
                    return 0;
                }
                Block neighbour = block.getWorld().getBlockAt(x, block.getY(), z);
                if (isShelf(neighbour) && powered(neighbour)
                        && neighbour.getBlockData() instanceof Directional other
                        && other.getFacing() == directional.getFacing()) {
                    String next = chain(neighbour.getBlockData());
                    if ("center".equals(next)) {
                        return 0;
                    }
                    if (("left".equals(part) && "right".equals(next))
                            || ("right".equals(part) && "left".equals(next))) {
                        pair = true;
                    }
                }
            }
            if (pair) {
                return 3;
            }
        }
        // Unknown/new chain data or unloaded neighbours: protect all potential inputs.
        return 0;
    }

    private static String chain(BlockData data) {
        // Paper uses SideChaining; Spigot exposes Shelf.SideChain. Serialized block
        // properties avoid linking either optional API (or relying on server versions).
        String serialized = data.getAsString();
        if (serialized != null) {
            for (String property : serialized.split("[\\[\\],]")) {
                if (property.startsWith("side_chain=")) {
                    return property.substring("side_chain=".length());
                }
            }
        }
        return "";
    }
}
