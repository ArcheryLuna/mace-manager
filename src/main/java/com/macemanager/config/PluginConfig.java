package com.macemanager.config;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class PluginConfig {
    private final JavaPlugin plugin;
    private int allowedMaces = -1;
    private boolean allowMaceEnchanting = true;
    private Map<String, Boolean> storage = Map.of();
    private boolean broadcastCrafted;
    private boolean cooldownEnabled;
    private boolean ignoreThorns = true;
    private int baseSeconds;
    private int minimumSeconds;
    private int maximumSeconds = 600;
    private Map<String, Integer> maceEnchantments = Map.of();
    private Map<String, Integer> armorEnchantments = Map.of();
    private Map<String, Integer> activeMaceEnchantments = Map.of();
    private Map<String, Integer> activeArmorEnchantments = Map.of();
    private int scanUnits;
    private long scanBudgetNanos;
    private int fullAuditTicks;
    private boolean asyncRegistryWrites;
    private boolean directChunkStates;
    private boolean logTimings;
    private boolean windChargeEnabled;
    private int windChargeSeconds = 30;
    private String prefix = "&6[MaceManager]&r ";
    private final Map<String, String> messages = new LinkedHashMap<>();

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration config = plugin.getConfig();
        int configuredLimit = config.getInt("allowed-maces", -1);
        allowedMaces = configuredLimit < 0 ? -1 : configuredLimit;
        allowMaceEnchanting = config.getBoolean("allow-mace-enchanting", true);
        Map<String, Boolean> destinations = new LinkedHashMap<>();
        for (String key : new String[]{"ender-chests", "shelves", "item-frames", "glow-item-frames",
                "chests", "barrels", "shulker-boxes", "bundles", "armor-stands", "hoppers",
                "droppers", "dispensers", "decorated-pots", "crafters"}) {
            boolean defaultAllowed = key.equals("ender-chests") || key.equals("shelves")
                    || key.equals("item-frames") || key.equals("glow-item-frames");
            destinations.put(key, config.getBoolean("mace-storage." + key, defaultAllowed));
        }
        storage = Map.copyOf(destinations);
        broadcastCrafted = config.getBoolean("broadcasts.crafted", false);
        cooldownEnabled = config.getBoolean("cooldown.enabled", false);
        ignoreThorns = config.getBoolean("cooldown.ignore-thorns", true);
        baseSeconds = Math.max(0, config.getInt("cooldown.base-seconds", 0));
        minimumSeconds = Math.max(0, config.getInt("cooldown.minimum-seconds", 0));
        maximumSeconds = config.getInt("cooldown.maximum-seconds", 600);
        if (maximumSeconds < minimumSeconds) {
            maximumSeconds = minimumSeconds;
        }
        maceEnchantments = rates(config, "cooldown.mace-enchantments");
        armorEnchantments = rates(config, "cooldown.armor-enchantments");
        activeMaceEnchantments = activeRates(maceEnchantments);
        activeArmorEnchantments = activeRates(armorEnchantments);
        scanUnits = Math.max(1, config.getInt("performance.scan-units-per-tick", 32));
        scanBudgetNanos = (long) (Math.max(0.1, Math.min(50.0,
                config.getDouble("performance.scan-budget-ms", 2.0))) * 1_000_000);
        fullAuditTicks = Math.max(100, config.getInt("performance.full-audit-ticks", 1200));
        asyncRegistryWrites = config.getBoolean("performance.async-registry-writes", false);
        directChunkStates = config.getBoolean("performance.paper-direct-states", false);
        logTimings = config.getBoolean("performance.log-timings", false);
        windChargeEnabled = config.getBoolean("wind-charge.enabled", false);
        windChargeSeconds = Math.max(0, config.getInt("wind-charge.seconds", 30));
        prefix = config.getString("messages.prefix", "&6[MaceManager]&r ");
        messages.clear();
        ConfigurationSection messageSection = config.getConfigurationSection("messages");
        if (messageSection != null) {
            for (String key : messageSection.getKeys(false)) {
                messages.put(key, messageSection.getString(key, ""));
            }
        }
    }

    public void warnUnknownEnchantments() {
        warn(maceEnchantments.keySet(), "cooldown.mace-enchantments");
        warn(armorEnchantments.keySet(), "cooldown.armor-enchantments");
    }

    public void setAllowedMaces(int amount) {
        allowedMaces = amount < 0 ? -1 : amount;
        if (persist("allowed-maces", Integer.toString(allowedMaces))) {
            load();
        }
    }

    public void setBaseSeconds(int seconds) {
        baseSeconds = Math.max(0, seconds);
        if (baseSeconds > 0) {
            cooldownEnabled = true;
        }
        if (persist(Map.of("cooldown.base-seconds", Integer.toString(baseSeconds),
                "cooldown.enabled", Boolean.toString(cooldownEnabled)))) {
            load();
        }
    }

    public void setAllowMaceEnchanting(boolean allowed) {
        allowMaceEnchanting = allowed;
        if (persist("allow-mace-enchanting", Boolean.toString(allowed))) {
            load();
        }
    }

    public String format(String key) {
        return format(key, Map.of());
    }

    public String format(String key, Map<String, String> tokens) {
        String message = prefix + messages.getOrDefault(key, "");
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            message = message.replace("%" + token.getKey() + "%", token.getValue());
        }
        return message;
    }

    public int allowedMaces() {
        return allowedMaces;
    }

    public boolean hasMaceLimit() {
        return allowedMaces >= 0;
    }

    public String maceLimitLabel() {
        return hasMaceLimit() ? Integer.toString(allowedMaces) : "unlimited";
    }

    public boolean allowMaceEnchanting() {
        return allowMaceEnchanting;
    }

    public boolean storageAllowed(String destination) {
        return storage.getOrDefault(destination, false);
    }

    public boolean broadcastCrafted() {
        return broadcastCrafted;
    }

    public boolean cooldownEnabled() {
        return cooldownEnabled;
    }

    public boolean ignoreThorns() {
        return ignoreThorns;
    }

    public int baseSeconds() {
        return baseSeconds;
    }

    public int minimumSeconds() {
        return minimumSeconds;
    }

    public int maximumSeconds() {
        return maximumSeconds;
    }

    public Map<String, Integer> maceEnchantments() {
        return maceEnchantments;
    }

    public Map<String, Integer> armorEnchantments() {
        return armorEnchantments;
    }

    public Map<String, Integer> activeMaceEnchantments() {
        return activeMaceEnchantments;
    }

    public Map<String, Integer> activeArmorEnchantments() {
        return activeArmorEnchantments;
    }

    public int scanUnits() {
        return scanUnits;
    }

    public long scanBudgetNanos() {
        return scanBudgetNanos;
    }

    public int fullAuditTicks() {
        return fullAuditTicks;
    }

    public boolean asyncRegistryWrites() {
        return asyncRegistryWrites;
    }

    public boolean directChunkStates() {
        return directChunkStates;
    }

    public boolean logTimings() {
        return logTimings;
    }

    static Map<String, Integer> activeRates(Map<String, Integer> rates) {
        Map<String, Integer> active = new LinkedHashMap<>();
        rates.forEach((key, value) -> {
            if (value != 0) {
                active.put(key, value);
            }
        });
        return Map.copyOf(active);
    }

    public boolean windChargeEnabled() {
        return windChargeEnabled;
    }

    public int windChargeSeconds() {
        return windChargeSeconds;
    }

    private boolean persist(String key, String value) {
        return persist(Map.of(key, value));
    }

    private boolean persist(Map<String, String> values) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        try {
            ConfigStore.replace(file, values);
            plugin.reloadConfig();
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save config.yml: " + exception.getMessage());
            return false;
        }
    }

    private Map<String, Integer> rates(FileConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return Map.of();
        }
        Map<String, Integer> rates = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            rates.put(key.toLowerCase(Locale.ROOT), section.getInt(key));
        }
        return Map.copyOf(rates);
    }

    private void warn(Iterable<String> keys, String path) {
        for (String key : keys) {
            try {
                if (Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key)) == null) {
                    plugin.getLogger().warning("Unknown enchantment '" + key + "' in " + path);
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Unknown enchantment '" + key + "' in " + path);
            }
        }
    }
}
