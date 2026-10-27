package com.macemanager.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginConfigTest {
    @TempDir
    Path directory;

    @Test
    void settingPositiveBaseEnablesCooldownAndSurvivesReload() throws Exception {
        Path file = directory.resolve("config.yml");
        try (var defaults = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(defaults);
            Files.copy(defaults, file);
        }
        JavaPlugin plugin = configuredPlugin(file);
        PluginConfig settings = new PluginConfig(plugin);
        settings.load();
        assertFalse(settings.cooldownEnabled());

        settings.setBaseSeconds(120);
        settings.load();

        assertTrue(settings.cooldownEnabled());
        assertEquals(120, settings.baseSeconds());
        assertFalse(settings.windChargeEnabled());
        assertEquals(30, settings.windChargeSeconds());
        assertTrue(Files.readString(file).contains("# Thorns armor still deals its reflected damage."));

        settings.setBaseSeconds(0);
        settings.load();
        assertTrue(settings.cooldownEnabled());
        assertEquals(0, settings.baseSeconds());
    }

    @Test
    void settingBaseAddsMissingCooldownSectionWithoutChangingOtherEnabledFlags() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "wind-charge:\n  enabled: true\n  seconds: 30\n");
        PluginConfig settings = new PluginConfig(configuredPlugin(file));
        settings.load();

        settings.setBaseSeconds(45);
        settings.load();

        assertTrue(settings.cooldownEnabled());
        assertEquals(45, settings.baseSeconds());
        assertTrue(settings.windChargeEnabled());
        assertEquals(30, settings.windChargeSeconds());
        assertNull(YamlConfiguration.loadConfiguration(file.toFile()).get("base-seconds"));
    }

    @Test
    void settingZeroBaseDoesNotEnableDisabledCooldowns() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "cooldown:\n  enabled: false\n");
        PluginConfig settings = new PluginConfig(configuredPlugin(file));
        settings.load();

        settings.setBaseSeconds(0);
        settings.load();

        assertFalse(settings.cooldownEnabled());
        assertEquals(0, settings.baseSeconds());
        assertEquals(0, YamlConfiguration.loadConfiguration(file.toFile()).getInt("cooldown.base-seconds", -1));
    }

    private JavaPlugin configuredPlugin(Path file) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getConfig()).thenAnswer(ignored -> YamlConfiguration.loadConfiguration(file.toFile()));
        return plugin;
    }

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
