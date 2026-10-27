package com.macemanager.permission;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class PermissionService {
    public static final String LIST = "macemanager.command.list";
    public static final String RELOAD = "macemanager.command.reload";
    public static final String SET_LIMIT = "macemanager.command.setlimit";
    public static final String SET_COOLDOWN = "macemanager.command.setcooldown";
    public static final String ENCHANTING = "macemanager.command.enchanting";
    public static final String REMOVE = "macemanager.command.remove";
    public static final String GIVE_UNTRACKED = "macemanager.command.giveuntracked";
    public static final String CLEAR_UNTRACKED = "macemanager.command.clearuntracked";
    public static final String BYPASS_COOLDOWN = "macemanager.bypass.cooldown";

    private final JavaPlugin plugin;

    public PermissionService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean allows(CommandSender sender, String node) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        Plugin luckPerms = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        if (luckPerms != null && luckPerms.isEnabled()) {
            return player.hasPermission(node);
        }
        return player.isOp();
    }
}
