package com.macemanager.cooldown;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.config.PluginConfig;
import com.macemanager.permission.PermissionService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaceCooldownListenerTest {
    private final AtomicLong now = new AtomicLong();
    private final CooldownService cooldowns = new CooldownService(now::get);
    private final MaceManagerPlugin plugin = mock(MaceManagerPlugin.class);
    private final PluginConfig settings = mock(PluginConfig.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final Player player = mock(Player.class);
    private final ItemStack held = mock(ItemStack.class);
    private final EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
    private final MaceCooldownListener listener = new MaceCooldownListener(plugin);

    @BeforeEach
    void setUp() {
        when(plugin.settings()).thenReturn(settings);
        when(plugin.cooldowns()).thenReturn(cooldowns);
        when(plugin.permissions()).thenReturn(permissions);
        when(settings.cooldownEnabled()).thenReturn(true);
        when(settings.ignoreThorns()).thenReturn(true);
        when(settings.baseSeconds()).thenReturn(120);
        when(settings.maximumSeconds()).thenReturn(600);
        when(settings.activeMaceEnchantments()).thenReturn(Map.of());
        when(settings.activeArmorEnchantments()).thenReturn(Map.of());
        when(settings.format(anyString(), anyMap())).thenReturn("Cooldown");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(held.getType()).thenReturn(Material.MACE);
        when(event.getDamager()).thenReturn(player);
        when(event.getCause()).thenReturn(DamageCause.ENTITY_ATTACK);
    }

    @Test
    void successfulHitStartsCooldownAndFurtherHitsAreBlockedUntilExpiry() {
        listener.blockSwingWhileCooling(event);
        verify(event, never()).setCancelled(true);
        listener.startCooldownAfterHit(event);
        assertEquals(120, cooldowns.maceRemainingSeconds(player));

        now.set(1_000);
        listener.blockSwingWhileCooling(event);
        verify(event).setCancelled(true);
        assertEquals(119, cooldowns.maceRemainingSeconds(player));

        clearInvocations(event);
        now.set(120_000);
        listener.blockSwingWhileCooling(event);
        verify(event, never()).setCancelled(true);
        assertFalse(cooldowns.maceActive(player));
    }

    @Test
    void thornsNeitherStartsRefreshesNorGetsBlockedByCooldown() {
        when(event.getCause()).thenReturn(DamageCause.THORNS);
        listener.startCooldownAfterHit(event);
        assertFalse(cooldowns.maceActive(player));
        cooldowns.startMace(player, 30);
        now.set(5_000);
        listener.blockSwingWhileCooling(event);
        listener.startCooldownAfterHit(event);
        verify(event, never()).setCancelled(true);
        assertEquals(25, cooldowns.maceRemainingSeconds(player));
    }

    @Test
    void bypassPermissionAllowsHitsWithoutRefreshingCooldown() {
        when(permissions.allows(player, PermissionService.BYPASS_COOLDOWN)).thenReturn(true);
        cooldowns.startMace(player, 30);
        now.set(5_000);
        listener.blockSwingWhileCooling(event);
        listener.startCooldownAfterHit(event);
        verify(event, never()).setCancelled(true);
        assertEquals(25, cooldowns.maceRemainingSeconds(player));
    }

    @Test
    void switchingToAnotherWeaponAllowsAttacksDuringMaceCooldown() {
        cooldowns.startMace(player, 30);
        when(held.getType()).thenReturn(Material.DIAMOND_SWORD);
        now.set(5_000);
        listener.blockSwingWhileCooling(event);
        listener.startCooldownAfterHit(event);
        verify(event, never()).setCancelled(true);
        assertEquals(25, cooldowns.maceRemainingSeconds(player));
    }
}
