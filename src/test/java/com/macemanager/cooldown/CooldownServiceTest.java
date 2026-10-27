package com.macemanager.cooldown;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CooldownServiceTest {
    private final AtomicLong now = new AtomicLong();
    private final CooldownService service = new CooldownService(now::get);
    private final Map<Material, Integer> ticks = new HashMap<>();
    private final UUID id = UUID.randomUUID();
    private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "setCooldown" -> {
                    ticks.put((Material) args[0], (Integer) args[1]);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });

    @Test
    void roundsUpRemainingSecondsAndExpiresAtTheBoundary() {
        assertTrue(service.startMace(player, 2));
        now.set(1_001L);
        assertEquals(1, service.maceRemainingSeconds(player));
        assertTrue(service.maceActive(player));
        now.set(2_000L);
        assertFalse(service.maceActive(player));
        assertEquals(0, service.maceRemainingSeconds(player));
    }

    @Test
    void reapplyRestoresBothCooldownsAndPruningKeepsActiveEntries() {
        service.startMace(player, 5);
        service.startWind(player, 10);
        now.set(5_000L);
        service.pruneExpired();
        ticks.clear();
        service.reapply(player);
        assertFalse(ticks.containsKey(Material.MACE));
        assertEquals(100, ticks.get(Material.WIND_CHARGE));
        now.set(10_000L);
        service.pruneExpired();
        assertFalse(service.windActive(player));
    }

    @Test
    void hugeCooldownCapsTicksAndNonPositiveCooldownDoesNotStart() {
        assertFalse(service.startMace(player, 0));
        service.startWind(player, -1);
        assertTrue(ticks.isEmpty());
        service.startMace(player, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, ticks.get(Material.MACE));
        assertEquals(Integer.MAX_VALUE, service.maceRemainingSeconds(player));
    }
}
