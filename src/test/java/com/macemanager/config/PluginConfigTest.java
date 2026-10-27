package com.macemanager.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginConfigTest {
    @Test
    void zeroRatesNeedNoEquipmentWhileNegativeModifiersRemainActive() {
        Map<String, Integer> configured = Map.of("density", 0, "breach", -5, "wind_burst", 10);
        Map<String, Integer> active = PluginConfig.activeRates(configured);
        assertEquals(Map.of("breach", -5, "wind_burst", 10), active);
        assertTrue(PluginConfig.activeRates(Map.of("density", 0, "unknown", 0)).isEmpty());
        assertEquals(3, configured.size()); // Original keys remain available for warnings.
        assertThrows(UnsupportedOperationException.class, () -> active.put("density", 5));
    }
}
