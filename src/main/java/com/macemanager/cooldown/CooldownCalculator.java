package com.macemanager.cooldown;

import java.math.BigInteger;
import java.util.Map;

public final class CooldownCalculator {
    /**
     * A total of zero or less does not start a cooldown, even when minimum-seconds is higher.
     * Positive totals are then clamped into the configured range.
     */
    public int seconds(
            int baseSeconds,
            int minimumSeconds,
            int maximumSeconds,
            Map<String, Integer> maceLevels,
            Map<String, Integer> maceRates,
            Map<String, Integer> armorLevels,
            Map<String, Integer> armorRates
    ) {
        return seconds(baseSeconds, minimumSeconds, maximumSeconds,
                contribution(maceLevels, maceRates), contribution(armorLevels, armorRates));
    }

    int seconds(int baseSeconds, int minimumSeconds, int maximumSeconds, long maceContribution, long armorContribution) {
        return seconds(baseSeconds, minimumSeconds, maximumSeconds,
                BigInteger.valueOf(maceContribution), BigInteger.valueOf(armorContribution));
    }

    int seconds(int baseSeconds, int minimumSeconds, int maximumSeconds,
                BigInteger maceContribution, BigInteger armorContribution) {
        BigInteger total = BigInteger.valueOf(baseSeconds).add(maceContribution).add(armorContribution);
        if (total.signum() <= 0) {
            return 0;
        }
        if (total.compareTo(BigInteger.valueOf(minimumSeconds)) < 0) {
            return minimumSeconds;
        }
        if (total.compareTo(BigInteger.valueOf(maximumSeconds)) > 0) {
            return maximumSeconds;
        }
        return total.intValue();
    }

    private BigInteger contribution(Map<String, Integer> levels, Map<String, Integer> rates) {
        if (levels == null || rates == null || levels.isEmpty() || rates.isEmpty()) {
            return BigInteger.ZERO;
        }
        BigInteger total = BigInteger.ZERO;
        for (Map.Entry<String, Integer> level : levels.entrySet()) {
            Integer rate = rates.get(level.getKey());
            if (rate == null || level.getValue() == null) {
                continue;
            }
            total = total.add(BigInteger.valueOf((long) rate * level.getValue()));
        }
        return total;
    }
}
