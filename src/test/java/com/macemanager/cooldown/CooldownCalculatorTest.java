package com.macemanager.cooldown;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CooldownCalculatorTest {
    private final CooldownCalculator calculator = new CooldownCalculator();

    @Test
    void largeModifiersClampWithoutIntegerOverflow() {
        assertEquals(600, calculator.seconds(Integer.MAX_VALUE, 0, 600,
                Map.of("density", 2), Map.of("density", Integer.MAX_VALUE), Map.of(), Map.of()));
        assertEquals(0, calculator.seconds(60, 30, 600,
                Map.of("density", 2), Map.of("density", Integer.MIN_VALUE), Map.of(), Map.of()));
    }

    @Test
    void multipleExtremeModifiersCannotWrapToDisableCooldown() {
        Map<String, Integer> levels = Map.of(
                "density", Integer.MAX_VALUE,
                "breach", Integer.MAX_VALUE,
                "wind_burst", Integer.MAX_VALUE);
        assertEquals(600, calculator.seconds(120, 0, 600, levels, levels, Map.of(), Map.of()));
        Map<String, Integer> reductions = Map.of(
                "density", Integer.MIN_VALUE,
                "breach", Integer.MIN_VALUE,
                "wind_burst", Integer.MIN_VALUE);
        assertEquals(0, calculator.seconds(120, 0, 600, levels, reductions, Map.of(), Map.of()));
    }

    @Test
    void extremeMaceAndArmorModifiersCancelBeforeClamping() {
        Map<String, Integer> levels = Map.of(
                "first", Integer.MAX_VALUE, "second", Integer.MAX_VALUE, "third", Integer.MAX_VALUE);
        Map<String, Integer> negativeRates = Map.of(
                "first", -Integer.MAX_VALUE, "second", -Integer.MAX_VALUE, "third", -Integer.MAX_VALUE);
        assertEquals(120, calculator.seconds(120, 0, 600, levels, levels, levels, negativeRates));
        assertEquals(600, calculator.seconds(120, 0, 600, Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(0, calculator.seconds(120, 0, 600, Long.MIN_VALUE, Long.MIN_VALUE));
    }

    @Test
    void directContributionsMatchAggregatedEnchantments() {
        assertEquals(86, calculator.seconds(60, 0, 600, 10L, 16L));
        assertEquals(0, calculator.seconds(10, 30, 600, -30L, 0L));
    }

    @Test
    void stacksMaceAndArmorEnchantments() {
        int seconds = calculator.seconds(
                60,
                0,
                600,
                Map.of("density", 2),
                Map.of("density", 5),
                Map.of("protection", 8),
                Map.of("protection", 2)
        );

        assertEquals(86, seconds);
    }

    @Test
    void negativeModifierShortensTheCooldown() {
        int seconds = calculator.seconds(
                60,
                0,
                600,
                Map.of("density", 2),
                Map.of("density", -5),
                Map.of(),
                Map.of()
        );

        assertEquals(50, seconds);
    }

    @Test
    void nonPositiveResultDoesNotStartACooldown() {
        int seconds = calculator.seconds(
                10,
                30,
                600,
                Map.of("breach", 3),
                Map.of("breach", -10),
                Map.of(),
                Map.of()
        );

        assertEquals(0, seconds);
    }

    @Test
    void clampsPositiveResultToMinimumAndMaximum() {
        assertEquals(20, calculator.seconds(5, 20, 600, Map.of(), Map.of(), Map.of(), Map.of()));
        assertEquals(600, calculator.seconds(
                100,
                0,
                600,
                Map.of("density", 10),
                Map.of("density", 100),
                Map.of(),
                Map.of()
        ));
    }
}
