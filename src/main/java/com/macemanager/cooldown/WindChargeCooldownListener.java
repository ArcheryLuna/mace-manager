package com.macemanager.cooldown;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.text.Notices;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class WindChargeCooldownListener implements Listener {
    private final MaceManagerPlugin plugin;

    public WindChargeCooldownListener(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (!plugin.settings().windChargeEnabled()) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.WIND_CHARGE) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.cooldowns().windActive(player)) {
            return;
        }
        event.setCancelled(true);
        Notices.actionBar(player, plugin.settings().format("wind-charge-active", Map.of(
                "seconds", Integer.toString(plugin.cooldowns().windRemainingSeconds(player))
        )));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void blockLaunchWhileCooling(ProjectileLaunchEvent event) {
        Player player = shooter(event);
        if (player == null || !plugin.cooldowns().windActive(player)) {
            return;
        }
        event.setCancelled(true);
        Notices.actionBar(player, plugin.settings().format("wind-charge-active", Map.of(
                "seconds", Integer.toString(plugin.cooldowns().windRemainingSeconds(player))
        )));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onLaunch(ProjectileLaunchEvent event) {
        Player player = shooter(event);
        if (player != null) {
            plugin.cooldowns().startWind(player, plugin.settings().windChargeSeconds());
        }
    }

    private Player shooter(ProjectileLaunchEvent event) {
        if (!plugin.settings().windChargeEnabled() || !(event.getEntity() instanceof WindCharge)) {
            return null;
        }
        if (!(event.getEntity().getShooter() instanceof Player player)) {
            return null;
        }
        return player;
    }
}
