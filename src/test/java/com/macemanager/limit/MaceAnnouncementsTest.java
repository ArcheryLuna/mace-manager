package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaceAnnouncementsTest {
    private final MaceManagerPlugin plugin = mock(MaceManagerPlugin.class);
    private final YamlConfiguration yaml = new YamlConfiguration();
    private final PluginConfig settings = new PluginConfig(plugin);
    private final MaceRegistry registry = mock(MaceRegistry.class);

    private MaceAnnouncements announcements() {
        when(plugin.getConfig()).thenReturn(yaml);
        when(plugin.settings()).thenReturn(settings);
        when(plugin.registry()).thenReturn(registry);
        when(registry.count()).thenReturn(2);
        yaml.set("messages.prefix", "");
        settings.load();
        return new MaceAnnouncements(plugin);
    }

    @Test
    void existingConfigBroadcastsCustomizedMessageWithColorsAndTokens() {
        yaml.set("messages.crafted-broadcast", "&a%player% made %amount% mace (%current%/%max%)");
        yaml.set("broadcasts.crafted", true);
        yaml.set("allowed-maces", 3);
        MaceAnnouncements announcements = announcements();
        assertTrue(settings.broadcastCrafted());
        try (var bukkit = mockStatic(Bukkit.class)) {
            announcements.crafted("Alex");
            bukkit.verify(() -> Bukkit.broadcastMessage("\u00a7aAlex made 1 mace (2/3)"));
            bukkit.verifyNoMoreInteractions();
        }
    }

    @Test
    void reloadingToggleSuppressesCraftingOnlyAndCanReenableIt() {
        yaml.set("broadcasts.crafted", true);
        yaml.set("allowed-maces", 3);
        yaml.set("messages.crafted-broadcast", "A mace was crafted (%current%/%max%)");
        yaml.set("messages.destroyed-broadcast", "Destroyed: %lastHolder%");
        MaceAnnouncements announcements = announcements();
        yaml.set("broadcasts.crafted", false);
        settings.load();
        assertFalse(settings.broadcastCrafted());

        try (var bukkit = mockStatic(Bukkit.class)) {
            announcements.crafted("Alex");
            bukkit.verifyNoInteractions();
            announcements.destroyed(new MaceRecord(UUID.randomUUID(), UUID.randomUUID(), "Alex"));
            bukkit.verify(() -> Bukkit.broadcastMessage("Destroyed: Alex"));

            yaml.set("broadcasts.crafted", true);
            settings.load();
            announcements.crafted("Alex");
            bukkit.verify(() -> Bukkit.broadcastMessage("A mace was crafted (2/3)"));
            bukkit.verifyNoMoreInteractions();
        }
    }
}
