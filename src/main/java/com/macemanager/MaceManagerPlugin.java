package com.macemanager;

import com.macemanager.command.MacesCommand;
import com.macemanager.config.PluginConfig;
import com.macemanager.cooldown.CooldownService;
import com.macemanager.cooldown.MaceCooldownListener;
import com.macemanager.cooldown.WindChargeCooldownListener;
import com.macemanager.limit.CraftingListener;
import com.macemanager.limit.ContainerListener;
import com.macemanager.limit.EnchantListener;
import com.macemanager.limit.MaceAnnouncements;
import com.macemanager.limit.MaceItems;
import com.macemanager.limit.MaceRecord;
import com.macemanager.limit.MaceRegistry;
import com.macemanager.limit.RecipeService;
import com.macemanager.limit.TrackingListener;
import com.macemanager.limit.ScanQueue;
import com.macemanager.limit.ChunkAccess;
import com.macemanager.permission.PermissionService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.logging.Level;

public final class MaceManagerPlugin extends JavaPlugin {
    private PluginConfig settings;
    private PermissionService permissions;
    private MaceItems maceItems;
    private MaceRegistry registry;
    private RecipeService recipes;
    private MaceAnnouncements announcements;
    private CooldownService cooldowns;
    private MaceCooldownListener maceCooldownListener;
    private TrackingListener tracking;
    private BukkitTask scanTask;
    private final ScanQueue scans = new ScanQueue();
    private ChunkAccess chunkAccess;
    private int ticksSinceAudit = 100;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new PluginConfig(this);
        settings.load();
        settings.warnUnknownEnchantments();
        permissions = new PermissionService(this);
        maceItems = new MaceItems(this);
        chunkAccess = new ChunkAccess(settings.directChunkStates());
        registry = new MaceRegistry(this, settings.asyncRegistryWrites());
        try {
            registry.load();
        } catch (IllegalStateException exception) {
            getLogger().log(Level.SEVERE, "Cannot initialize mace storage; disabling MaceManager before enforcement starts", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        announcements = new MaceAnnouncements(this);
        recipes = new RecipeService(this);
        recipes.capture();
        cooldowns = new CooldownService();
        tracking = new TrackingListener(this);

        getServer().getPluginManager().registerEvents(new CraftingListener(this), this);
        getServer().getPluginManager().registerEvents(new ContainerListener(this), this);
        getServer().getPluginManager().registerEvents(tracking, this);
        getServer().getPluginManager().registerEvents(new EnchantListener(this), this);
        maceCooldownListener = new MaceCooldownListener(this);
        getServer().getPluginManager().registerEvents(maceCooldownListener, this);
        getServer().getPluginManager().registerEvents(new WindChargeCooldownListener(this), this);

        MacesCommand command = new MacesCommand(this);
        PluginCommand maces = getCommand("maces");
        if (maces != null) {
            maces.setExecutor(command);
            maces.setTabCompleter(command);
        }

        tracking.reconcileLoadedWorlds();
        recipes.sync();
        scanTask = getServer().getScheduler().runTaskTimer(this, () -> {
            scans.tick(settings.scanUnits(), settings.scanBudgetNanos());
            ticksSinceAudit++;
            if (ticksSinceAudit % 20 == 0) {
                tracking.reconcileDirty();
            }
            if ((tracking.readyForAudit() && tracking.auditDue() && ticksSinceAudit >= 100)
                    || ticksSinceAudit >= settings.fullAuditTicks()) {
                tracking.reconcileAll();
                recipes.sync(); // Repair changes by other plugins during the audit.
                cooldowns.pruneExpired();
                ticksSinceAudit = 0;
            }
            if (ticksSinceAudit % 100 == 0) registry.save(); // Retry failed writes even without mutations.
        }, 1L, 1L);
        String limit = settings.hasMaceLimit() ? Integer.toString(settings.allowedMaces()) : "unlimited";
        getLogger().info("Tracking " + registry.count() + "/" + limit + " maces.");
    }

    @Override
    public void onDisable() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        scans.clear();
        if (registry != null) {
            registry.close();
        }
        if (settings != null && settings.logTimings() && tracking != null) {
            getLogger().info("Maximum plugin timings (ms): scan tick=" + scans.maximumTickNanos() / 1_000_000.0
                    + ", scan unit=" + scans.maximumUnitNanos() / 1_000_000.0
                    + ", full audit elapsed=" + tracking.maximumAuditNanos() / 1_000_000.0
                    + ", dirty pass=" + tracking.maximumDirtyNanos() / 1_000_000.0
                    + ", cooldown calculation=" + maceCooldownListener.maximumCalculationNanos() / 1_000_000.0
                    + ", registry write=" + registry.maximumWriteNanos() / 1_000_000.0);
        }
        if (recipes != null) {
            recipes.restore();
        }
    }

    public void reloadSettings() {
        reloadConfig();
        settings.load();
        settings.warnUnknownEnchantments();
        chunkAccess = new ChunkAccess(settings.directChunkStates());
        recipes.refresh();
        tracking.reconcileLoadedWorlds();
        for (var player : getServer().getOnlinePlayers()) {
            tracking.markDirty(player);
        }
    }

    public void destroyMace(UUID id) {
        MaceRecord removed = registry.remove(id);
        if (removed == null) {
            return;
        }
        announcements.destroyed(removed);
        recipes.requestSync();
    }

    public void forgetMace(UUID id) {
        if (registry.revoke(id) != null) {
            recipes.requestSync();
        }
    }

    public ScanQueue scans() {
        return scans;
    }

    public ChunkAccess chunkAccess() {
        return chunkAccess;
    }

    public TrackingListener tracking() {
        return tracking;
    }

    public PluginConfig settings() {
        return settings;
    }

    public PermissionService permissions() {
        return permissions;
    }

    public MaceItems maceItems() {
        return maceItems;
    }

    public MaceRegistry registry() {
        return registry;
    }

    public RecipeService recipes() {
        return recipes;
    }

    public MaceAnnouncements announcements() {
        return announcements;
    }

    public CooldownService cooldowns() {
        return cooldowns;
    }
}
