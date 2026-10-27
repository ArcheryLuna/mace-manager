package com.macemanager.command;

import com.macemanager.MaceManagerPlugin;
import com.macemanager.limit.MaceRecord;
import com.macemanager.limit.MaceSweeper;
import com.macemanager.permission.PermissionService;
import com.macemanager.text.Notices;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MacesCommand implements CommandExecutor, TabCompleter {
    private final MaceManagerPlugin plugin;

    public MacesCommand(MaceManagerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return list(sender);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "setlimit" -> setLimit(sender, args);
            case "setcooldown" -> setCooldown(sender, args);
            case "enchanting" -> enchanting(sender, args);
            case "remove" -> remove(sender, args);
            case "giveuntracked" -> giveUntracked(sender);
            case "clearuntracked" -> clearUntracked(sender);
            default -> {
                Notices.send(sender, plugin.settings().format("usage", Map.of("usage", "/maces")));
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(subcommands(sender), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("enchanting") && plugin.permissions().allows(sender, PermissionService.ENCHANTING)) {
            return filter(List.of("on", "off"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove") && plugin.permissions().allows(sender, PermissionService.REMOVE)) {
            List<String> ids = new ArrayList<>();
            for (MaceRecord record : plugin.registry().all()) {
                ids.add(shortId(record.id()));
            }
            return filter(ids, args[1]);
        }
        return List.of();
    }

    private boolean list(CommandSender sender) {
        if (!plugin.permissions().allows(sender, PermissionService.LIST)) {
            Notices.send(sender, plugin.settings().format("no-permission"));
            return true;
        }
        Notices.send(sender, plugin.settings().format("list-header", Map.of(
                "current", Integer.toString(plugin.registry().count()),
                "max", plugin.settings().maceLimitLabel()
        )));
        List<MaceRecord> records = new ArrayList<>(plugin.registry().all());
        records.sort(Comparator.comparing(MaceRecord::holderName, String.CASE_INSENSITIVE_ORDER));
        if (records.isEmpty()) {
            Notices.send(sender, plugin.settings().format("list-none"));
        }
        Set<UUID> holders = new HashSet<>();
        for (MaceRecord record : records) {
            if (record.holder() != null) {
                holders.add(record.holder());
            }
            String state = record.holder() != null && plugin.getServer().getPlayer(record.holder()) != null ? "online" : "offline";
            Notices.send(sender, plugin.settings().format("list-holder", Map.of(
                    "player", record.holderName(),
                    "state", state,
                    "id", shortId(record.id())
            )));
        }
        List<Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        for (Player player : online) {
            if (holders.contains(player.getUniqueId())) {
                continue;
            }
            if (carrying(player)) {
                Notices.send(sender, plugin.settings().format("list-holder", Map.of(
                        "player", player.getName(),
                        "state", "online",
                        "id", "untracked"
                )));
                continue;
            }
            Notices.send(sender, plugin.settings().format("list-without", Map.of("player", player.getName())));
        }
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!plugin.permissions().allows(sender, PermissionService.RELOAD)) {
            Notices.send(sender, plugin.settings().format("no-permission"));
            return true;
        }
        plugin.reloadSettings();
        Notices.send(sender, plugin.settings().format("reload"));
        return true;
    }

    private boolean setLimit(CommandSender sender, String[] args) {
        if (!plugin.permissions().allows(sender, PermissionService.SET_LIMIT)) {
            Notices.send(sender, plugin.settings().format("no-permission"));
            return true;
        }
        Integer amount = parseLimit(args);
        if (amount == null) {
            Notices.send(sender, plugin.settings().format("usage", Map.of("usage", "/maces setlimit <-1|amount>")));
            return true;
        }
        plugin.settings().setAllowedMaces(amount);
        plugin.recipes().sync();
        Notices.send(sender, plugin.settings().format("setlimit", Map.of("limit", plugin.settings().maceLimitLabel())));
        return true;
    }

    private boolean setCooldown(CommandSender sender, String[] args) {
        if (!plugin.permissions().allows(sender, PermissionService.SET_COOLDOWN)) {
            Notices.send(sender, plugin.settings().format("no-permission"));
            return true;
        }
        Integer seconds = parseNonNegative(args);
        if (seconds == null) {
            Notices.send(sender, plugin.settings().format("usage", Map.of("usage", "/maces setcooldown <seconds>")));
            return true;
        }
        plugin.settings().setBaseSeconds(seconds);
        Notices.send(sender, plugin.settings().format("setcooldown", Map.of("seconds", Integer.toString(seconds))));
        return true;
    }

    private boolean enchanting(CommandSender sender, String[] args) {
        if (!plugin.permissions().allows(sender, PermissionService.ENCHANTING)) {
            Notices.send(sender, plugin.settings().format("no-permission"));
            return true;
        }
        if (args.length < 2) {
            Notices.send(sender, plugin.settings().format("usage", Map.of("usage", "/maces enchanting <on|off>")));
            return true;
        }
        Boolean enabled = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable" -> true;
            case "off", "false", "disable" -> false;
            default -> null;
        };
        if (enabled == null) {
            Notices.send(sender, plugin.settings().format("usage", Map.of("usage", "/maces enchanting <on|off>")));
            return true;
        }
        plugin.settings().setAllowMaceEnchanting(enabled);
        Notices.send(sender, plugin.settings().format(enabled ? "enchanting-on" : "enchanting-off"));
        return true;
    }

    private boolean remove(CommandSender sender, String[] args) {
        if (!plugin.permissions().allows(sender, PermissionService.REMOVE)) {
            Notices.send(sender, plugin.settings().format("no-permission"));
            return true;
        }
        if (args.length < 2) {
            Notices.send(sender, plugin.settings().format("usage", Map.of("usage", "/maces remove <id>")));
            return true;
        }
        List<MaceRecord> matches = plugin.registry().matching(args[1]);
        if (matches.isEmpty()) {
            Notices.send(sender, plugin.settings().format("not-found"));
            return true;
        }
        if (matches.size() > 1) {
            Notices.send(sender, plugin.settings().format("ambiguous-id"));
            return true;
        }
        MaceRecord record = matches.get(0);
        plugin.forgetMace(record.id());
        Notices.send(sender, plugin.settings().format("sweep-started"));
        new MaceSweeper(plugin).remove(stack -> record.id().equals(plugin.maceItems().trackedId(stack)),
                removed -> Notices.send(sender, plugin.settings().format("removed", Map.of("id", shortId(record.id())))),
                () -> Notices.send(sender, plugin.settings().format("sweep-failed")));
        return true;
    }

    private boolean giveUntracked(CommandSender sender) {
        if (!plugin.permissions().allows(sender, PermissionService.GIVE_UNTRACKED)) {
            Notices.send(sender, plugin.settings().format("no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            Notices.send(sender, plugin.settings().format("players-only"));
            return true;
        }
        ItemStack mace = plugin.maceItems().createUntracked();
        for (ItemStack leftover : player.getInventory().addItem(mace).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        Notices.send(sender, plugin.settings().format("gave-untracked"));
        return true;
    }

    private boolean clearUntracked(CommandSender sender) {
        if (!plugin.permissions().allows(sender, PermissionService.CLEAR_UNTRACKED)) {
            Notices.send(sender, plugin.settings().format("no-permission"));
            return true;
        }
        Notices.send(sender, plugin.settings().format("sweep-started"));
        new MaceSweeper(plugin).remove(plugin.maceItems()::isUntracked,
                removed -> Notices.send(sender, plugin.settings().format("cleared-untracked", Map.of("amount", Integer.toString(removed)))),
                () -> Notices.send(sender, plugin.settings().format("sweep-failed")));
        return true;
    }

    private boolean carrying(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (plugin.maceItems().isMace(stack)) {
                return true;
            }
        }
        return plugin.maceItems().isMace(player.getItemOnCursor());
    }

    private Integer parseLimit(String[] args) {
        if (args.length < 2) {
            return null;
        }
        try {
            int value = Integer.parseInt(args[1]);
            return value < -1 ? null : value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer parseNonNegative(String[] args) {
        if (args.length < 2) {
            return null;
        }
        try {
            int value = Integer.parseInt(args[1]);
            return value < 0 ? null : value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<String> subcommands(CommandSender sender) {
        List<String> commands = new ArrayList<>();
        PermissionService permissions = plugin.permissions();
        if (permissions.allows(sender, PermissionService.RELOAD)) {
            commands.add("reload");
        }
        if (permissions.allows(sender, PermissionService.SET_LIMIT)) {
            commands.add("setlimit");
        }
        if (permissions.allows(sender, PermissionService.SET_COOLDOWN)) {
            commands.add("setcooldown");
        }
        if (permissions.allows(sender, PermissionService.ENCHANTING)) {
            commands.add("enchanting");
        }
        if (permissions.allows(sender, PermissionService.REMOVE)) {
            commands.add("remove");
        }
        if (permissions.allows(sender, PermissionService.GIVE_UNTRACKED)) {
            commands.add("giveuntracked");
        }
        if (permissions.allows(sender, PermissionService.CLEAR_UNTRACKED)) {
            commands.add("clearuntracked");
        }
        return commands;
    }

    private List<String> filter(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
