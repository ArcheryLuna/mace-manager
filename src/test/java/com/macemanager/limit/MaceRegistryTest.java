package com.macemanager.limit;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MaceRegistryTest {
    @TempDir Path directory;

    @Test
    void coalescesChangesAndPersistsOnlyTheFinalState() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Path file = fixture(first, second);
        List<Runnable> tasks = new ArrayList<>();
        MaceRegistry registry = registry(file, tasks);
        registry.load();

        registry.remove(first);
        registry.remove(second);
        assertEquals(1, tasks.size());
        assertEquals(2, storedCount(file));

        tasks.removeFirst().run();
        MaceRegistry reloaded = registry(file, tasks);
        reloaded.load();
        assertEquals(0, reloaded.count());
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count()); // Database and untouched YAML import source.
        }
    }

    @Test
    void explicitSaveFlushesBeforeScheduledTaskAndCleanSavesDoNothing() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = fixture(id);
        List<Runnable> tasks = new ArrayList<>();
        MaceRegistry registry = registry(file, tasks);
        registry.load();
        registry.remove(id);
        registry.save();
        byte[] saved = Files.readAllBytes(file);
        var timestamp = Files.getLastModifiedTime(file);

        tasks.removeFirst().run();
        registry.save();
        assertArrayEquals(saved, Files.readAllBytes(file));
        assertEquals(timestamp, Files.getLastModifiedTime(file));
    }

    @Test
    void failedSaveRemainsDirtyAndCanBeRetried() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = fixture(id);
        List<Runnable> tasks = new ArrayList<>();
        MaceRegistry registry = registry(file, tasks);
        registry.load();
        registry.remove(id);
        Path savedDatabase = directory.resolve("saved.db");
        Files.move(file, savedDatabase);
        Files.createDirectory(file);
        Path blocker = Files.writeString(file.resolve("blocker"), "keep");

        tasks.removeFirst().run();
        assertTrue(Files.exists(blocker));
        Files.delete(blocker);
        Files.delete(file);
        Files.move(savedDatabase, file);
        registry.save();
        assertTrue(Files.isRegularFile(file));
        assertEquals(0, storedCount(file));
    }

    @Test
    void droppedCoordinatesHandleNegativePositionsWithoutLoadingAChunk() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = fixture(id);
        List<Runnable> tasks = new ArrayList<>();
        MaceRegistry registry = registry(file, tasks);
        registry.load();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) {
                        return "world";
                    }
                    throw new AssertionError("Unexpected world access: " + method.getName());
                });

        registry.markDropped(id, new Location(world, -0.1, 64, -16.1));
        registry.markDropped(id, new Location(world, -1, 64, -17));
        assertEquals(1, tasks.size());
        tasks.removeFirst().run();
        MaceRegistry reloaded = registry(file, tasks);
        reloaded.load();
        MaceRecord record = reloaded.all().iterator().next();
        assertTrue(record.dropped());
        assertEquals("world", record.dropWorld());
        assertEquals(-1, record.dropChunkX());
        assertEquals(-2, record.dropChunkZ());
    }

    @Test
    void unknownRemovalDoesNotScheduleAWriteAndMatchingAcceptsCompactIds() throws Exception {
        UUID id = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");
        Path file = fixture(id);
        List<Runnable> tasks = new ArrayList<>();
        MaceRegistry registry = registry(file, tasks);
        registry.load();
        assertNull(registry.remove(UUID.randomUUID()));
        assertTrue(tasks.isEmpty());
        assertEquals(1, registry.matching("ABCDEF123456").size());
        assertEquals(1, registry.matching("abcdef12-3456").size());
    }

    @Test
    void backgroundWriterUsesImmutableSnapshotsAndShutdownPersistsLatestState() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = fixture(id);
        List<Runnable> tasks = new ArrayList<>();
        var writer = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        writer.submit(() -> {
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer never released");
            } catch (InterruptedException exception) {
                throw new AssertionError(exception);
            }
        });
        MaceRegistry registry = new MaceRegistry(file.toFile(), Logger.getLogger("MaceRegistryTest"), tasks::add, writer);
        try {
            registry.load();
            World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getName")) return "world";
                        throw new AssertionError("Worker must not access the world: " + method.getName());
                    });
            registry.markDropped(id, new Location(world, 16, 64, 32));
            tasks.removeFirst().run(); // Captures chunk 1,2; worker is deliberately blocked.
            registry.markDropped(id, new Location(world, 160, 64, 320));
            registry.save(); // Must not queue an unbounded backlog of snapshots.
            release.countDown();
            int firstChunk = writer.submit(() -> storedChunk(file, id, "drop_chunk_x")).get(10, TimeUnit.SECONDS);
            assertEquals(1, firstChunk);
            registry.close();
            assertEquals(10, storedChunk(file, id, "drop_chunk_x"));
            assertEquals(20, storedChunk(file, id, "drop_chunk_z"));
            assertTrue(writer.isShutdown());
            tasks.forEach(Runnable::run); // Scheduled callbacks after close cannot write stale state.
            assertEquals(10, storedChunk(file, id, "drop_chunk_x"));
        } finally {
            release.countDown();
            registry.close();
            writer.shutdownNow();
        }
    }

    @Test
    void failedBackgroundWriteIsRetriedOnShutdown() throws Exception {
        UUID id = UUID.randomUUID();
        Path file = fixture(id);
        var writer = Executors.newSingleThreadExecutor();
        MaceRegistry registry = new MaceRegistry(file.toFile(), Logger.getLogger("MaceRegistryTest"), task -> {}, writer);
        try {
            registry.load();
            registry.remove(id);
            Path savedDatabase = directory.resolve("saved.db");
            Files.move(file, savedDatabase);
            Files.createDirectory(file);
            Path blocker = Files.writeString(file.resolve("blocker"), "keep");
            registry.save();
            writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertTrue(Files.exists(blocker));
            Files.delete(blocker);
            Files.delete(file);
            Files.move(savedDatabase, file);
            registry.close();
            assertTrue(Files.isRegularFile(file));
            assertEquals(0, storedCount(file));
        } finally {
            registry.close();
            writer.shutdownNow();
        }
    }

    private MaceRegistry registry(Path file, List<Runnable> tasks) {
        return new MaceRegistry(file.toFile(), Logger.getLogger("MaceRegistryTest"), tasks::add);
    }

    private int storedCount(Path file) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM maces")) {
            result.next();
            return result.getInt(1);
        }
    }

    private int storedChunk(Path file, UUID id, String column) throws Exception {
        // Column names are fixed test constants; record values are parameters.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.prepareStatement("SELECT " + column + " FROM maces WHERE id = ?")) {
            statement.setString(1, id.toString());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private Path fixture(UUID... ids) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        for (UUID id : ids) {
            yaml.set("maces." + id + ".holder", UUID.randomUUID().toString());
            yaml.set("maces." + id + ".holder-name", "Player");
        }
        yaml.save(directory.resolve("maces.yml").toFile());
        Path file = directory.resolve("maces.db");
        new SqliteMaceStore(file.toFile()).load();
        return file;
    }
}
