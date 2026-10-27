package com.macemanager.text;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

public final class Notices {
    private static final ActionBarAccess ACTION_BAR = ActionBarAccess.detect();
    private Notices() {
    }

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(color(message));
    }

    public static void actionBar(Player player, String message) {
        String colored = color(message);
        if (!sendActionBar(player, colored)) {
            player.sendMessage(colored);
        }
    }

    public static String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private static boolean sendActionBar(Player player, String colored) {
        if (ACTION_BAR == null) {
            return false;
        }
        try {
            Object spigot = ACTION_BAR.spigot().invoke(player);
            Object components = ACTION_BAR.fromLegacyText().invoke(null, colored);
            ACTION_BAR.send().invoke(spigot, ACTION_BAR.type(), components);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private record ActionBarAccess(Method spigot, Method fromLegacyText, Method send, Object type) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static ActionBarAccess detect() {
            try {
                Class messageType = Class.forName("net.md_5.bungee.api.ChatMessageType");
                Object type = Enum.valueOf(messageType, "ACTION_BAR");
                Class<?> textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent");
                Class<?> componentArray = Class.forName("[Lnet.md_5.bungee.api.chat.BaseComponent;");
                Method spigot = Player.class.getMethod("spigot");
                return new ActionBarAccess(spigot, textComponent.getMethod("fromLegacyText", String.class),
                        spigot.getReturnType().getMethod("sendMessage", messageType, componentArray), type);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                return null;
            }
        }
    }
}
