package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.text.Notices;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;

public final class EnchantListener implements Listener {
    private final MaceManagerPlugin plugin;

    public EnchantListener(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (plugin.settings().allowMaceEnchanting() || !plugin.maceItems().isMace(event.getItem())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (plugin.settings().allowMaceEnchanting() || !plugin.maceItems().isMace(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        if (event.getEnchanter() instanceof Player player) {
            Notices.send(player, plugin.settings().format("enchanting-blocked"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAnvil(PrepareAnvilEvent event) {
        if (plugin.settings().allowMaceEnchanting()) {
            return;
        }
        AnvilInventory inventory = event.getInventory();
        if (plugin.maceItems().isMace(inventory.getFirstItem()) || plugin.maceItems().isMace(inventory.getSecondItem())) {
            event.setResult(null);
        }
    }
}
