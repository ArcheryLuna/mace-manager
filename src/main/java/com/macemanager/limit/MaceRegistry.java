package com.macemanager.limit;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class MaceRegistry {
    private final Logger logger;
    private final Consumer<Runnable> schedule;
    private final File file;
    private final Map<UUID, MaceRecord> maces = new LinkedHashMap<>();
    private long revision;
    private final AtomicLong persistedRevision = new AtomicLong();
    private final AtomicLong maximumWriteNanos = new AtomicLong();
    private final ExecutorService writer;
    private Future<?> pendingWrite;
    private boolean closed;
    private boolean saveScheduled;

    public MaceRegistry(JavaPlugin plugin, boolean asynchronous) {
        this(new File(plugin.getDataFolder(), "maces.yml"), plugin.getLogger(),
                task -> plugin.getServer().getScheduler().runTask(plugin, task), asynchronous);
    }

    MaceRegistry(File file, Logger logger, Consumer<Runnable> schedule) {
        this(file, logger, schedule, false);
    }

    MaceRegistry(File file, Logger logger, Consumer<Runnable> schedule, boolean asynchronous) {
        this(file, logger, schedule, asynchronous ? Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "MaceManager-registry");
            thread.setDaemon(true);
            return thread;
        }) : null);
    }

    MaceRegistry(File file, Logger logger, Consumer<Runnable> schedule, ExecutorService writer) {
        this.file = file;
        this.logger = logger;
        this.schedule = schedule;
        this.writer = writer;
    }

    public void load() {
        maces.clear();
        revision = 0;
        persistedRevision.set(0);
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("maces");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String holderRaw = section.getString(key + ".holder", "");
                UUID holder = holderRaw == null || holderRaw.isBlank() ? null : UUID.fromString(holderRaw);
                MaceRecord record = new MaceRecord(id, holder, section.getString(key + ".holder-name", "unknown"));
                if (section.getBoolean(key + ".dropped", false)) {
                    record.setDrop(
                            section.getString(key + ".drop-world"),
                            section.getInt(key + ".drop-chunk-x"),
                            section.getInt(key + ".drop-chunk-z")
                    );
                }
                maces.put(id, record);
            } catch (IllegalArgumentException exception) {
                logger.warning("Skipping invalid mace id " + key);
            }
        }
    }

    public void save() {
        if (closed || revision == persistedRevision.get()) {
            return;
        }
        if (pendingWrite != null && !pendingWrite.isDone()) {
            scheduleSave();
            return;
        }
        // Copy primitive/string/UUID values on the server thread, never mutable records.
        List<Snapshot> snapshot = maces.values().stream().map(record -> new Snapshot(
                record.id(), record.holder(), record.holderName(), record.dropped(),
                record.dropWorld(), record.dropChunkX(), record.dropChunkZ())).toList();
        long version = revision;
        if (writer == null) {
            write(snapshot, version);
        } else {
            pendingWrite = writer.submit(() -> write(snapshot, version));
        }
    }

    public long maximumWriteNanos() {
        return maximumWriteNanos.get();
    }

    /** Drain the old snapshot before capturing/writing the final state. Server thread only. */
    public void close() {
        if (closed) {
            return;
        }
        boolean interrupted = false;
        while (pendingWrite != null) {
            try {
                pendingWrite.get();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            } catch (ExecutionException exception) {
                logger.warning("Registry writer failed: " + exception.getCause());
                break;
            }
        }
        save();
        while (pendingWrite != null) {
            try {
                pendingWrite.get();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            } catch (ExecutionException exception) {
                logger.warning("Final registry write failed: " + exception.getCause());
                break;
            }
        }
        closed = true;
        if (writer != null) {
            writer.shutdown();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(List<Snapshot> snapshot, long version) {
        long start = System.nanoTime();
        YamlConfiguration yaml = new YamlConfiguration();
        for (Snapshot record : snapshot) {
            String path = "maces." + record.id();
            yaml.set(path + ".holder", record.holder() == null ? "" : record.holder().toString());
            yaml.set(path + ".holder-name", record.holderName());
            yaml.set(path + ".dropped", record.dropped());
            if (record.dropped()) {
                yaml.set(path + ".drop-world", record.dropWorld());
                yaml.set(path + ".drop-chunk-x", record.dropChunkX());
                yaml.set(path + ".drop-chunk-z", record.dropChunkZ());
            }
        }
        Path temporary = null;
        try {
            Path target = file.toPath().toAbsolutePath();
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "maces-", ".tmp");
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            persistedRevision.set(version);
        } catch (IOException exception) {
            logger.warning("Could not save maces.yml: " + exception.getMessage());
        } finally {
            maximumWriteNanos.accumulateAndGet(System.nanoTime() - start, Math::max);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    logger.warning("Could not remove temporary registry file: " + exception.getMessage());
                }
            }
        }
    }

    // Mutable registry state and all scheduling stay on the server thread.
    private void changed() {
        revision++;
        scheduleSave();
    }

    private void scheduleSave() {
        if (!saveScheduled && !closed) {
            saveScheduled = true;
            schedule.accept(() -> {
                saveScheduled = false;
                save();
            });
        }
    }

    private record Snapshot(UUID id, UUID holder, String holderName, boolean dropped,
                            String dropWorld, int dropChunkX, int dropChunkZ) {
    }

    public int count() {
        return maces.size();
    }

    public boolean contains(UUID id) {
        return id != null && maces.containsKey(id);
    }

    public Collection<MaceRecord> all() {
        return List.copyOf(maces.values());
    }

    public void register(UUID id, Player holder) {
        maces.put(id, new MaceRecord(id, holder.getUniqueId(), holder.getName()));
        changed();
    }

    public MaceRecord remove(UUID id) {
        MaceRecord removed = maces.remove(id);
        if (removed != null) {
            changed();
        }
        return removed;
    }

    public void hold(UUID id, Player player) {
        MaceRecord record = maces.get(id);
        if (record != null && record.setHolder(player.getUniqueId(), player.getName())) {
            changed();
        }
    }

    public void markDropped(UUID id, Location location) {
        MaceRecord record = maces.get(id);
        if (record == null || location.getWorld() == null) {
            return;
        }
        if (record.markDropped(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            changed();
        }
    }

    public List<MaceRecord> matching(String token) {
        String needle = token.toLowerCase(Locale.ROOT);
        List<MaceRecord> matches = new ArrayList<>();
        for (MaceRecord record : maces.values()) {
            String full = record.id().toString();
            if (full.startsWith(needle) || full.replace("-", "").startsWith(needle)) {
                matches.add(record);
            }
        }
        return matches;
    }
}
