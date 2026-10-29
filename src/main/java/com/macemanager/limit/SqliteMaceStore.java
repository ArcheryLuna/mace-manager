package com.macemanager.limit;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.sqlite.JDBC;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** JDBC connections never escape an operation; no Bukkit world/entity access here. */
final class SqliteMaceStore {
    private static final int SCHEMA_VERSION = 3;
    private final File database;
    private final File legacy;

    SqliteMaceStore(File database) {
        this.database = database.getAbsoluteFile();
        this.legacy = new File(this.database.getParentFile(), "maces.yml");
    }

    private Connection connect() throws SQLException {
        // Instantiate our bundled driver rather than relying on global DriverManager
        // discovery or whichever SQLite driver another plugin registered first.
        Connection connection = new JDBC().connect("jdbc:sqlite:" + database, new Properties());
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 1000");
            statement.execute("PRAGMA synchronous = FULL");
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
        return connection;
    }

    List<MaceSnapshot> load() throws IOException, SQLException {
        Files.createDirectories(database.toPath().getParent());
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                initialize(connection);
                List<MaceSnapshot> records = read(connection);
                connection.commit();
                return records;
            } catch (IOException | SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private void initialize(Connection connection) throws IOException, SQLException {
        try (var statement = connection.createStatement()) {
            int version;
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                result.next();
                version = result.getInt(1);
                if (version < 0 || version > SCHEMA_VERSION) {
                    throw new SQLException("Unsupported mace database schema version: " + version);
                }
            }
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS maces (
                        id TEXT PRIMARY KEY NOT NULL,
                        holder TEXT,
                        holder_name TEXT NOT NULL,
                        dropped INTEGER NOT NULL CHECK (dropped IN (0, 1)),
                        drop_world TEXT,
                        drop_chunk_x INTEGER NOT NULL,
                        drop_chunk_z INTEGER NOT NULL,
                        CHECK (dropped = 0 OR drop_world IS NOT NULL)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS registry_metadata (
                        key TEXT PRIMARY KEY NOT NULL,
                        value TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS revoked_maces (id TEXT PRIMARY KEY NOT NULL)");
            if (version < 2) {
                // Transactional upgrade: existing owners and dropped chunks remain unchanged.
                statement.executeUpdate("ALTER TABLE maces ADD COLUMN location_state TEXT NOT NULL DEFAULT 'CARRIED'");
                statement.executeUpdate("ALTER TABLE maces ADD COLUMN location_world TEXT");
                statement.executeUpdate("ALTER TABLE maces ADD COLUMN location_chunk_x INTEGER NOT NULL DEFAULT 0");
                statement.executeUpdate("ALTER TABLE maces ADD COLUMN location_chunk_z INTEGER NOT NULL DEFAULT 0");
                statement.executeUpdate("ALTER TABLE maces ADD COLUMN block_x INTEGER");
                statement.executeUpdate("ALTER TABLE maces ADD COLUMN block_y INTEGER");
                statement.executeUpdate("ALTER TABLE maces ADD COLUMN block_z INTEGER");
                statement.executeUpdate("ALTER TABLE maces ADD COLUMN location_entity TEXT");
                statement.executeUpdate("ALTER TABLE maces ADD COLUMN inventory_owner TEXT");
                statement.executeUpdate("ALTER TABLE maces ADD COLUMN storage_type TEXT");
                statement.executeUpdate("""
                        UPDATE maces SET location_state = 'DROPPED', location_world = drop_world,
                            location_chunk_x = drop_chunk_x, location_chunk_z = drop_chunk_z
                        WHERE dropped = 1
                        """);
            }
            boolean initialized;
            try (var result = statement.executeQuery(
                    "SELECT value FROM registry_metadata WHERE key = 'initialized'")) {
                initialized = result.next();
                if (initialized && !"1".equals(result.getString(1))) {
                    throw new SQLException("Invalid mace database initialization marker");
                }
            }
            if (!initialized) {
                try (var result = statement.executeQuery("SELECT COUNT(*) FROM maces")) {
                    result.next();
                    if (result.getInt(1) != 0) {
                        throw new SQLException("Mace database has records but no initialization marker");
                    }
                }
                insert(connection, readLegacy());
                statement.executeUpdate("INSERT INTO registry_metadata VALUES ('initialized', '1')");
            }
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
        }
    }

    private List<MaceSnapshot> readLegacy() throws IOException {
        if (!legacy.exists()) {
            return List.of();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(legacy);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("Cannot import invalid maces.yml", exception);
        }
        ConfigurationSection section = yaml.getConfigurationSection("maces");
        if (section == null) {
            if (!yaml.getKeys(false).isEmpty()) {
                throw new IOException("Cannot import maces.yml: missing maces section");
            }
            return List.of();
        }
        List<MaceSnapshot> records = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            try {
                if (!section.isConfigurationSection(key)) {
                    throw new IllegalArgumentException("Expected a mace record");
                }
                UUID id = UUID.fromString(key);
                String rawHolder = section.getString(key + ".holder", "");
                UUID holder = rawHolder == null || rawHolder.isBlank() ? null : UUID.fromString(rawHolder);
                boolean dropped = section.getBoolean(key + ".dropped", false);
                String world = dropped ? section.getString(key + ".drop-world") : null;
                if (dropped && (world == null || world.isBlank()
                        || !section.isInt(key + ".drop-chunk-x") || !section.isInt(key + ".drop-chunk-z"))) {
                    throw new IllegalArgumentException("Missing dropped mace location");
                }
                MaceRecord record = new MaceRecord(id, holder, section.getString(key + ".holder-name", "unknown"));
                if (dropped) {
                    record.setDrop(world, section.getInt(key + ".drop-chunk-x"), section.getInt(key + ".drop-chunk-z"));
                }
                records.add(MaceSnapshot.from(record));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Cannot import invalid mace record " + key, exception);
            }
        }
        return records;
    }

    private List<MaceSnapshot> read(Connection connection) throws SQLException {
        List<MaceSnapshot> records = new ArrayList<>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT * FROM maces ORDER BY rowid")) {
            while (result.next()) {
                try {
                    String holder = result.getString("holder");
                    records.add(new MaceSnapshot(UUID.fromString(result.getString("id")),
                            holder == null ? null : UUID.fromString(holder), result.getString("holder_name"),
                            new MaceLocation(MaceLocation.State.valueOf(result.getString("location_state")),
                                    result.getString("location_world"), result.getInt("location_chunk_x"),
                                    result.getInt("location_chunk_z"), (Integer) result.getObject("block_x"),
                                    (Integer) result.getObject("block_y"), (Integer) result.getObject("block_z"),
                                    uuid(result.getString("location_entity")), uuid(result.getString("inventory_owner")),
                                    result.getString("storage_type"))));
                } catch (IllegalArgumentException | NullPointerException exception) {
                    throw new SQLException("Invalid mace database record", exception);
                }
            }
        }
        return records;
    }

    void write(List<MaceSnapshot> snapshot) throws SQLException {
        write(snapshot, List.of());
    }

    List<UUID> revoked() throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = connect(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT id FROM revoked_maces")) {
            while (result.next()) {
                try {
                    ids.add(UUID.fromString(result.getString(1)));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("Invalid revoked mace identity", exception);
                }
            }
        }
        return ids;
    }

    void write(List<MaceSnapshot> snapshot, List<UUID> revoked) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM maces");
                }
                insert(connection, snapshot);
                // Revocations are permanent and committed atomically with active records.
                try (var statement = connection.prepareStatement("INSERT OR IGNORE INTO revoked_maces (id) VALUES (?)")) {
                    for (UUID id : revoked) {
                        statement.setString(1, id.toString());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private void insert(Connection connection, List<MaceSnapshot> snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO maces (id, holder, holder_name, dropped, drop_world, drop_chunk_x, drop_chunk_z,
                    location_state, location_world, location_chunk_x, location_chunk_z,
                    block_x, block_y, block_z, location_entity, inventory_owner, storage_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (MaceSnapshot record : snapshot) {
                statement.setString(1, record.id().toString());
                statement.setString(2, record.holder() == null ? null : record.holder().toString());
                statement.setString(3, record.holderName());
                statement.setInt(4, record.dropped() ? 1 : 0);
                statement.setString(5, record.dropped() ? record.dropWorld() : null);
                statement.setInt(6, record.dropChunkX());
                statement.setInt(7, record.dropChunkZ());
                MaceLocation location = record.location();
                statement.setString(8, location.state().name());
                statement.setString(9, location.world());
                statement.setInt(10, location.chunkX());
                statement.setInt(11, location.chunkZ());
                statement.setObject(12, location.blockX());
                statement.setObject(13, location.blockY());
                statement.setObject(14, location.blockZ());
                statement.setString(15, location.entity() == null ? null : location.entity().toString());
                statement.setString(16, location.inventoryOwner() == null ? null : location.inventoryOwner().toString());
                statement.setString(17, location.storageType());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            original.addSuppressed(exception);
        }
    }
}
