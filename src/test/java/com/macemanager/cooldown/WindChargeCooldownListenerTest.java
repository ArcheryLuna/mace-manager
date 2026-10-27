package com.macemanager.cooldown;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.config.PluginConfig;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WindChargeCooldownListenerTest {
    private final AtomicLong now = new AtomicLong();
    private final CooldownService cooldowns = new CooldownService(now::get);
    private final MaceManagerPlugin plugin = mock(MaceManagerPlugin.class);
    private final PluginConfig settings = mock(PluginConfig.class);
    private final Player player = mock(Player.class);
    private final WindCharge charge = mock(WindCharge.class);
    private final WindChargeCooldownListener listener = new WindChargeCooldownListener(plugin);

    @BeforeEach
    void setUp() {
        when(plugin.settings()).thenReturn(settings);
        when(plugin.cooldowns()).thenReturn(cooldowns);
        when(settings.windChargeEnabled()).thenReturn(true);
        when(settings.windChargeSeconds()).thenReturn(30);
        when(settings.format(anyString(), anyMap())).thenReturn("Cooldown active");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(charge.getShooter()).thenReturn(player);
    }

    @Test
    void directLaunchWithoutInteractionStartsCooldownAndBlocksNextLaunch() {
        ProjectileLaunchEvent first = new ProjectileLaunchEvent(charge);
        listener.blockLaunchWhileCooling(first);
        assertFalse(first.isCancelled());
        listener.onLaunch(first);
        assertEquals(30, cooldowns.windRemainingSeconds(player));

        now.set(5_000);
        ProjectileLaunchEvent second = new ProjectileLaunchEvent(charge);
        listener.blockLaunchWhileCooling(second);
        assertTrue(second.isCancelled());
        assertEquals(25, cooldowns.windRemainingSeconds(player));

        now.set(30_000);
        ProjectileLaunchEvent afterExpiry = new ProjectileLaunchEvent(charge);
        listener.blockLaunchWhileCooling(afterExpiry);
        assertFalse(afterExpiry.isCancelled());
        listener.onLaunch(afterExpiry);
        assertEquals(30, cooldowns.windRemainingSeconds(player));
    }

    @Test
    void disabledFeatureDoesNotBlockOrStartCooldown() {
        cooldowns.startWind(player, 30);
        when(settings.windChargeEnabled()).thenReturn(false);
        now.set(5_000);
        ProjectileLaunchEvent event = new ProjectileLaunchEvent(charge);
        listener.blockLaunchWhileCooling(event);
        listener.onLaunch(event);
        assertFalse(event.isCancelled());
        assertEquals(25, cooldowns.windRemainingSeconds(player));
    }

    @Test
    void unrelatedProjectilesAndNonPlayerChargesAreUnaffected() {
        ProjectileLaunchEvent arrow = new ProjectileLaunchEvent(mock(Arrow.class));
        when(charge.getShooter()).thenReturn(null);
        ProjectileLaunchEvent unownedCharge = new ProjectileLaunchEvent(charge);
        for (ProjectileLaunchEvent event : new ProjectileLaunchEvent[]{arrow, unownedCharge}) {
            listener.blockLaunchWhileCooling(event);
            listener.onLaunch(event);
            assertFalse(event.isCancelled());
        }
        assertFalse(cooldowns.windActive(player));
    }
}
