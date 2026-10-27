package com.macemanager.limit;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.text.Notices;
import org.bukkit.Bukkit;

import java.util.Map;

public final class MaceAnnouncements {
    private final MaceManagerPlugin plugin;

    public MaceAnnouncements(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    public void crafted(String playerName) {
        broadcast("crafted-broadcast", playerName, playerName);
    }

    public void destroyed(MaceRecord record) {
        broadcast("destroyed-broadcast", record.holderName(), record.holderName());
    }

    private void broadcast(String key, String playerName, String lastHolder) {
        String message = plugin.settings().format(key, Map.of(
                "player", playerName,
                "lastHolder", lastHolder,
                "current", Integer.toString(plugin.registry().count()),
                "max", Integer.toString(plugin.settings().allowedMaces()),
                "amount", "1"
        ));
        Bukkit.broadcastMessage(Notices.color(message));
    }
}
