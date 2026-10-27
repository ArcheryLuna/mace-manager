package com.macemanager.cooldown;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class CooldownService {
    private final Map<UUID, Long> maceExpiry = new HashMap<>();
    private final Map<UUID, Long> windExpiry = new HashMap<>();
    private final LongSupplier clock;

    public CooldownService() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    CooldownService(LongSupplier clock) {
        this.clock = clock;
    }

    public void pruneExpired() {
        long now = clock.getAsLong();
        maceExpiry.values().removeIf(until -> until <= now);
        windExpiry.values().removeIf(until -> until <= now);
    }

    public boolean maceActive(Player player) {
        return remainingMillis(maceExpiry, player.getUniqueId()) > 0;
    }

    public int maceRemainingSeconds(Player player) {
        return remainingSeconds(maceExpiry, player.getUniqueId());
    }

    public boolean startMace(Player player, int seconds) {
        if (seconds <= 0) {
            return false;
        }
        maceExpiry.put(player.getUniqueId(), clock.getAsLong() + seconds * 1000L);
        player.setCooldown(Material.MACE, ticks(seconds));
        return true;
    }

    public boolean windActive(Player player) {
        return remainingMillis(windExpiry, player.getUniqueId()) > 0;
    }

    public int windRemainingSeconds(Player player) {
        return remainingSeconds(windExpiry, player.getUniqueId());
    }

    public void startWind(Player player, int seconds) {
        if (seconds <= 0) {
            return;
        }
        windExpiry.put(player.getUniqueId(), clock.getAsLong() + seconds * 1000L);
        player.setCooldown(Material.WIND_CHARGE, ticks(seconds));
    }

    public void reapply(Player player) {
        int maceSeconds = maceRemainingSeconds(player);
        if (maceSeconds > 0) {
            player.setCooldown(Material.MACE, ticks(maceSeconds));
        }
        int windSeconds = windRemainingSeconds(player);
        if (windSeconds > 0) {
            player.setCooldown(Material.WIND_CHARGE, ticks(windSeconds));
        }
    }

    private int remainingSeconds(Map<UUID, Long> expiry, UUID playerId) {
        long millis = remainingMillis(expiry, playerId);
        if (millis <= 0) {
            return 0;
        }
        return (int) ((millis + 999L) / 1000L);
    }

    private long remainingMillis(Map<UUID, Long> expiry, UUID playerId) {
        Long until = expiry.get(playerId);
        if (until == null) {
            return 0;
        }
        long remaining = until - clock.getAsLong();
        if (remaining <= 0) {
            expiry.remove(playerId);
            return 0;
        }
        return remaining;
    }

    private int ticks(int seconds) {
        long ticks = seconds * 20L;
        if (ticks > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) ticks;
    }
}
