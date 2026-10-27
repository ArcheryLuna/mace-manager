package com.macemanager.cooldown;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.permission.PermissionService;
import com.macemanager.text.Notices;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.math.BigInteger;
import java.util.Map;

public final class MaceCooldownListener implements Listener {
    private final MaceManagerPlugin plugin;
    private long maximumCalculationNanos;
    private final CooldownCalculator calculator = new CooldownCalculator();

    public MaceCooldownListener(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void blockSwingWhileCooling(EntityDamageByEntityEvent event) {
        Player player = attacker(event);
        if (player == null || !plugin.settings().cooldownEnabled()) {
            return;
        }
        if (plugin.permissions().allows(player, PermissionService.BYPASS_COOLDOWN)) {
            return;
        }
        if (!plugin.cooldowns().maceActive(player)) {
            return;
        }
        event.setCancelled(true);
        Notices.actionBar(player, plugin.settings().format("cooldown-active", Map.of(
                "seconds", Integer.toString(plugin.cooldowns().maceRemainingSeconds(player))
        )));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void startCooldownAfterHit(EntityDamageByEntityEvent event) {
        Player player = attacker(event);
        if (player == null || !plugin.settings().cooldownEnabled()) {
            return;
        }
        if (plugin.permissions().allows(player, PermissionService.BYPASS_COOLDOWN)) {
            return;
        }
        long calculationStart = plugin.settings().logTimings() ? System.nanoTime() : 0;
        int seconds = calculator.seconds(
                plugin.settings().baseSeconds(),
                plugin.settings().minimumSeconds(),
                plugin.settings().maximumSeconds(),
                maceContribution(player, plugin.settings().activeMaceEnchantments()),
                armorContribution(player, plugin.settings().activeArmorEnchantments())
        );
        if (plugin.settings().logTimings()) {
            maximumCalculationNanos = Math.max(maximumCalculationNanos, System.nanoTime() - calculationStart);
        }
        if (!plugin.cooldowns().startMace(player, seconds)) {
            return;
        }
        Notices.actionBar(player, plugin.settings().format("cooldown-applied", Map.of(
                "seconds", Integer.toString(seconds)
        )));
    }

    /**
     * Thorns damage is reported as this player attacking. It must not start,
     * refresh, or get cancelled by the mace cooldown.
     */
    private Player attacker(EntityDamageByEntityEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.THORNS) {
            return null;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return null;
        }
        if (!(event.getDamager() instanceof Player player)) {
            return null;
        }
        if (player.getInventory().getItemInMainHand().getType() != Material.MACE) {
            return null;
        }
        return player;
    }

    public long maximumCalculationNanos() {
        return maximumCalculationNanos;
    }

    private BigInteger maceContribution(Player player, Map<String, Integer> rates) {
        return rates.isEmpty() ? BigInteger.ZERO : contribution(player.getInventory().getItemInMainHand(), rates);
    }

    private BigInteger armorContribution(Player player, Map<String, Integer> rates) {
        if (rates.isEmpty()) {
            return BigInteger.ZERO;
        }
        BigInteger total = BigInteger.ZERO;
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            total = total.add(contribution(piece, rates));
        }
        return total;
    }

    private BigInteger contribution(ItemStack stack, Map<String, Integer> rates) {
        if (stack == null || rates.isEmpty()) {
            return BigInteger.ZERO;
        }
        BigInteger total = BigInteger.ZERO;
        for (Map.Entry<Enchantment, Integer> entry : stack.getEnchantments().entrySet()) {
            total = total.add(BigInteger.valueOf(
                    (long) rates.getOrDefault(entry.getKey().getKey().getKey(), 0) * entry.getValue()));
        }
        return total;
    }
}
