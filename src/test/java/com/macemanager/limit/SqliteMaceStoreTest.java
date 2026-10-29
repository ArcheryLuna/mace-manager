package com.macemanager.limit;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SqliteMaceStoreTest {
    @TempDir Path directory;

    private Path database() {
        return directory.resolve("maces.db");
    }

    private SqliteMaceStore store() {
        return new SqliteMaceStore(database().toFile());
    }

    @Test
    void upgradesVersionTwoAndCommitsRevocationWithRemovalAtomically() throws Exception {
        store().load();
        MaceSnapshot record = new MaceSnapshot(UUID.randomUUID(), UUID.randomUUID(), "Owner", false, null, 0, 0);
        store().write(List.of(record));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE revoked_maces");
            statement.execute("PRAGMA user_version = 2");
        }
        assertEquals(List.of(record), store().load());
        assertTrue(store().revoked().isEmpty());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER fail_revoke BEFORE INSERT ON revoked_maces BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        }
        assertThrows(SQLException.class, () -> store().write(List.of(), List.of(record.id())));
        assertEquals(List.of(record), store().load());
        assertTrue(store().revoked().isEmpty());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER fail_revoke");
        }
        store().write(List.of(), List.of(record.id()));
        assertTrue(store().load().isEmpty());
        assertEquals(List.of(record.id()), store().revoked());
    }

    @Test
    void importsOwnershipAndDroppedLocationWithoutModifyingLegacyFile() throws Exception {
        UUID heldId = UUID.randomUUID();
        UUID droppedId = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("maces." + heldId + ".holder", holder.toString());
        yaml.set("maces." + heldId + ".holder-name", "OfflinePlayer");
        yaml.set("maces." + droppedId + ".holder", "");
        yaml.set("maces." + droppedId + ".holder-name", "O'Brien's mace");
        yaml.set("maces." + droppedId + ".dropped", true);
        yaml.set("maces." + droppedId + ".drop-world", "world'); DROP TABLE maces; --");
        yaml.set("maces." + droppedId + ".drop-chunk-x", -2);
        yaml.set("maces." + droppedId + ".drop-chunk-z", 14);
        Path legacy = directory.resolve("maces.yml");
        yaml.save(legacy.toFile());
        byte[] original = Files.readAllBytes(legacy);

        List<MaceSnapshot> records = store().load();
        assertEquals(2, records.size());
        assertEquals(new MaceSnapshot(heldId, holder, "OfflinePlayer", false, null, 0, 0), records.get(0));
        assertEquals(new MaceSnapshot(droppedId, null, "O'Brien's mace", true,
                "world'); DROP TABLE maces; --", -2, 14), records.get(1));
        assertEquals(records, store().load());
        assertArrayEquals(original, Files.readAllBytes(legacy));
    }

    @Test
    void emptyDatabaseNeverReimportsOldYamlAfterDeletingLastRecord() throws Exception {
        UUID id = UUID.randomUUID();
        Files.writeString(directory.resolve("maces.yml"), "maces:\n  " + id + ":\n    holder-name: Old\n");
        assertEquals(1, store().load().size());
        store().write(List.of());
        assertTrue(store().load().isEmpty());
        // Even a malformed stale YAML source is ignored after initialization.
        Files.writeString(directory.resolve("maces.yml"), "maces: [broken");
        assertTrue(store().load().isEmpty());
    }

    @Test
    void freshDatabaseDoesNotImportYamlAddedAfterInitialization() throws Exception {
        assertTrue(store().load().isEmpty());
        Files.writeString(directory.resolve("maces.yml"), "maces: [broken");
        assertTrue(store().load().isEmpty());
    }

    @Test
    void invalidRecordAbortsEntireImportAndAllowsRetryAfterCorrection() throws Exception {
        UUID id = UUID.randomUUID();
        Path legacy = directory.resolve("maces.yml");
        String valid = "maces:\n  " + id + ":\n    holder-name: Owner\n";
        Files.writeString(legacy, valid + "  invalid-id:\n    holder-name: Broken\n");
        MaceRegistry registry = new MaceRegistry(database().toFile(), Logger.getLogger("MigrationTest"), task -> {});
        assertThrows(IllegalStateException.class, registry::load);
        assertEquals(0, registry.count());
        registry.close();
        Files.writeString(legacy, valid);
        List<MaceSnapshot> records = store().load();
        assertEquals(1, records.size());
        assertEquals(id, records.getFirst().id());
    }

    @Test
    void malformedYamlAndIncompleteDroppedLocationsAbortMigration() throws Exception {
        Path legacy = directory.resolve("maces.yml");
        Files.writeString(legacy, "maces: [broken");
        assertThrows(java.io.IOException.class, () -> store().load());
        Files.writeString(legacy, "maces:\n  " + UUID.randomUUID() + ":\n    dropped: true\n");
        assertThrows(java.io.IOException.class, () -> store().load());
        Files.writeString(legacy, "{}\n");
        assertTrue(store().load().isEmpty());
    }

    @Test
    void failedSnapshotRollsBackDeletionAndCanBeRetried() throws Exception {
        store().load();
        MaceSnapshot record = new MaceSnapshot(UUID.randomUUID(), UUID.randomUUID(), "Owner", false, null, 0, 0);
        store().write(List.of(record));
        // A duplicate primary key fails after DELETE and the first INSERT.
        assertThrows(SQLException.class, () -> store().write(List.of(record, record)));
        assertEquals(List.of(record), store().load());
        MaceSnapshot dropped = new MaceSnapshot(record.id(), record.holder(), record.holderName(), true, "world", -1, -2);
        store().write(List.of(dropped));
        assertEquals(List.of(dropped), store().load());
    }

    @Test
    void unreadableDatabaseFailsWithoutOverwritingItOrFallingBackToYaml() throws Exception {
        byte[] invalid = "This is not a SQLite database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(database(), invalid);
        Files.writeString(directory.resolve("maces.yml"), "{}\n");
        MaceRegistry registry = new MaceRegistry(database().toFile(), Logger.getLogger("CorruptDatabaseTest"), task -> {});
        assertThrows(IllegalStateException.class, registry::load);
        registry.close();
        assertArrayEquals(invalid, Files.readAllBytes(database()));
    }

    @Test
    void storageAndDisplayLocationsRoundTripWithOfflineOwners() throws Exception {
        UUID owner = UUID.randomUUID();
        List<MaceLocation> locations = List.of(
                MaceLocation.carried(),
                MaceLocation.dropped("unloaded-world", -2, 14),
                MaceLocation.block(MaceLocation.State.STORED, "SHELF", "unloaded-world", -17, 70, 33),
                MaceLocation.entity(MaceLocation.State.DISPLAYED, "GLOW_ITEM_FRAME", "unloaded-world",
                        -2, 2, UUID.randomUUID()),
                MaceLocation.entity(MaceLocation.State.STORED, "CHEST_MINECART", "unloaded-world",
                        -2, 2, UUID.randomUUID()),
                MaceLocation.playerStorage("ENDER_CHEST", owner));
        List<MaceSnapshot> records = locations.stream()
                .map(location -> new MaceSnapshot(UUID.randomUUID(), owner, "OfflinePlayer", location)).toList();
        store().load();
        store().write(records);
        assertEquals(records, store().load());
        assertEquals(records, store().load().stream().map(MaceSnapshot::record).map(MaceSnapshot::from).toList());
        // A failed replacement must preserve all stored/displayed references as well as owners.
        assertThrows(SQLException.class, () -> store().write(List.of(records.get(2), records.get(2))));
        assertEquals(records, store().load());
    }

    @Test
    void upgradesVersionOneWithoutChangingOwnersOrDroppedChunks() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID held = UUID.randomUUID();
        UUID dropped = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE maces (
                        id TEXT PRIMARY KEY NOT NULL, holder TEXT, holder_name TEXT NOT NULL,
                        dropped INTEGER NOT NULL CHECK (dropped IN (0, 1)), drop_world TEXT,
                        drop_chunk_x INTEGER NOT NULL, drop_chunk_z INTEGER NOT NULL,
                        CHECK (dropped = 0 OR drop_world IS NOT NULL))
                    """);
            statement.executeUpdate("CREATE TABLE registry_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO registry_metadata VALUES ('initialized', '1')");
            statement.execute("PRAGMA user_version = 1");
            try (var insert = connection.prepareStatement("INSERT INTO maces VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (UUID id : List.of(held, dropped)) {
                    boolean ground = id.equals(dropped);
                    insert.setString(1, id.toString());
                    insert.setString(2, owner.toString());
                    insert.setString(3, "OfflinePlayer");
                    insert.setInt(4, ground ? 1 : 0);
                    insert.setString(5, ground ? "unloaded-world" : null);
                    insert.setInt(6, ground ? -2 : 0);
                    insert.setInt(7, ground ? 14 : 0);
                    insert.executeUpdate();
                }
            }
        }
        // The initialized database must ignore stale legacy data during the upgrade.
        Files.writeString(directory.resolve("maces.yml"), "maces: [broken");
        List<MaceSnapshot> expected = List.of(
                new MaceSnapshot(held, owner, "OfflinePlayer", MaceLocation.carried()),
                new MaceSnapshot(dropped, owner, "OfflinePlayer", MaceLocation.dropped("unloaded-world", -2, 14)));
        assertEquals(expected, store().load());
        assertEquals(expected, store().load());
        store().write(expected);
        assertEquals(expected, store().load());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(result.next());
            assertEquals(3, result.getInt(1));
        }
    }

    @Test
    void invalidLocationAbortsLoadingWithoutErasingOwnership() throws Exception {
        store().load();
        MaceSnapshot record = new MaceSnapshot(UUID.randomUUID(), UUID.randomUUID(), "OfflinePlayer",
                MaceLocation.playerStorage("ENDER_CHEST", UUID.randomUUID()));
        store().write(List.of(record));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE maces SET location_state = 'INVALID'");
        }
        assertThrows(SQLException.class, () -> store().load());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT holder FROM maces")) {
            assertTrue(result.next());
            assertEquals(record.holder().toString(), result.getString(1));
        }
    }

    @Test
    void refusesFutureSchemasWithoutOverwritingRecords() throws Exception {
        store().load();
        MaceSnapshot record = new MaceSnapshot(UUID.randomUUID(), null, "Owner", false, null, 0, 0);
        store().write(List.of(record));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 4");
        }
        assertThrows(SQLException.class, () -> store().load());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id FROM maces")) {
            assertTrue(result.next());
            assertEquals(record.id().toString(), result.getString(1));
        }
    }
}
