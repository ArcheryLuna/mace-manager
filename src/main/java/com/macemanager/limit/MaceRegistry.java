package com.macemanager.limit;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
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
    private final SqliteMaceStore store;
    private final Map<UUID, MaceRecord> maces = new LinkedHashMap<>();
    private long revision;
    private final AtomicLong persistedRevision = new AtomicLong();
    private final AtomicLong maximumWriteNanos = new AtomicLong();
    private final ExecutorService writer;
    private Future<?> pendingWrite;
    private boolean closed;
    private boolean saveScheduled;

    public MaceRegistry(JavaPlugin plugin, boolean asynchronous) {
        this(new File(plugin.getDataFolder(), "maces.db"), plugin.getLogger(),
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
        this.store = new SqliteMaceStore(file);
        this.logger = logger;
        this.schedule = schedule;
        this.writer = writer;
    }

    public void load() {
        if (closed || revision != 0 || pendingWrite != null) {
            throw new IllegalStateException("Registry can only be loaded before mutations");
        }
        try {
            List<MaceSnapshot> records = store.load();
            maces.clear();
            for (MaceSnapshot snapshot : records) {
                MaceRecord record = snapshot.record();
                maces.put(record.id(), record);
            }
            persistedRevision.set(0);
        } catch (IOException | SQLException exception) {
            // Abort startup before registering cleanup listeners. An empty registry
            // after a read failure would misclassify legitimate maces as illegal.
            throw new IllegalStateException("Could not load maces.db or import maces.yml; tracking was not started", exception);
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
        List<MaceSnapshot> snapshot = maces.values().stream().map(MaceSnapshot::from).toList();
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

    private void write(List<MaceSnapshot> snapshot, long version) {
        long start = System.nanoTime();
        try {
            store.write(snapshot);
            persistedRevision.set(version);
        } catch (SQLException exception) {
            logger.warning("Could not save maces.db: " + exception.getMessage());
        } finally {
            maximumWriteNanos.accumulateAndGet(System.nanoTime() - start, Math::max);
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

    /** Server thread only. Retains ownership and uses the normal batched persistence path. */
    public void locate(UUID id, MaceLocation location) {
        MaceRecord record = maces.get(id);
        if (record != null && record.setLocation(location)) {
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
