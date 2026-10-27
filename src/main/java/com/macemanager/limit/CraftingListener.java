package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.text.Notices;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

public final class CraftingListener implements Listener {
    private final MaceManagerPlugin plugin;

    public CraftingListener(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || result.getType() != Material.MACE) {
            return;
        }
        if (plugin.registry().count() >= plugin.settings().allowedMaces()) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getRecipe().getResult();
        if (result.getType() != Material.MACE || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory() instanceof CraftingInventory crafting)) {
            return;
        }
        if (!event.isShiftClick() && !player.getItemOnCursor().getType().isAir()) {
            return;
        }

        int perCraft = Math.max(1, result.getAmount());
        int possibleCrafts = event.isShiftClick() ? maxCrafts(crafting) : 1;
        int room = Math.max(0, plugin.settings().allowedMaces() - plugin.registry().count());
        int craftsByLimit = room / perCraft;
        int craftsBySpace = event.isShiftClick() ? emptySlots(player) / perCraft : 1;
        int crafts = Math.min(possibleCrafts, Math.min(craftsByLimit, craftsBySpace));
        if (crafts <= 0) {
            event.setCancelled(true);
            crafting.setResult(null);
            if (craftsByLimit <= 0) {
                Notices.send(player, plugin.settings().format("limit-reached"));
            }
            return;
        }

        event.setCancelled(true);
        crafting.setResult(null);
        consume(crafting, crafts);
        for (int craft = 0; craft < crafts; craft++) {
            for (int made = 0; made < perCraft; made++) {
                MaceItems.TaggedMace tagged = plugin.maceItems().createTracked();
                plugin.registry().register(tagged.id(), player);
                if (!event.isShiftClick() && craft == 0 && made == 0) {
                    player.setItemOnCursor(tagged.stack());
                } else {
                    give(player, tagged.stack());
                }
                plugin.announcements().crafted(player.getName());
            }
        }
        plugin.recipes().sync();
        refresh(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        ItemStack result = event.getResult();
        if (result != null && result.getType() == Material.MACE) {
            // A crafter would put the mace straight into a container.
            event.setCancelled(true);
        }
    }

    private int maxCrafts(CraftingInventory inventory) {
        int max = Integer.MAX_VALUE;
        for (ItemStack ingredient : inventory.getMatrix()) {
            if (ingredient == null || ingredient.getType().isAir()) {
                continue;
            }
            max = Math.min(max, ingredient.getAmount());
        }
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    private int emptySlots(Player player) {
        int empty = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                empty++;
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand == null || offhand.getType().isAir()) {
            empty++;
        }
        return empty;
    }

    private void consume(CraftingInventory inventory, int crafts) {
        ItemStack[] matrix = inventory.getMatrix();
        for (int slot = 0; slot < matrix.length; slot++) {
            ItemStack ingredient = matrix[slot];
            if (ingredient == null || ingredient.getType().isAir()) {
                continue;
            }
            int left = ingredient.getAmount() - crafts;
            if (left <= 0) {
                matrix[slot] = null;
            } else {
                ingredient.setAmount(left);
            }
        }
        inventory.setMatrix(matrix);
    }

    private void give(Player player, ItemStack stack) {
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack left : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    @SuppressWarnings("deprecation")
    private void refresh(Player player) {
        player.updateInventory();
    }
}
