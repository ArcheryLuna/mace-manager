package com.macemanager.limit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SqliteCrashRecoveryTest {
    @TempDir Path directory;

    @Test
    void abruptProcessExitRollsBackUncommittedRemovalAndRevocation() throws Exception {
        Path database = directory.resolve("crash.db");
        var store = new SqliteMaceStore(database.toFile());
        store.load();
        var record = new MaceSnapshot(UUID.randomUUID(), UUID.randomUUID(), "Offline owner",
                MaceLocation.block(MaceLocation.State.STORED, "SHELF", "world", 32, 64, 0));
        store.write(List.of(record));
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(CrashWriter.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String classpath = classes + File.pathSeparator + System.getProperty("macemanager.test.pluginJar");
        Path output = directory.resolve("writer.log");
        Process process = new ProcessBuilder(javaExecutable, "-cp", classpath, CrashWriter.class.getName(),
                database.toString(), record.id().toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Crash writer timed out");
            assertEquals(0, process.exitValue(), () -> {
                try { return java.nio.file.Files.readString(output); }
                catch (java.io.IOException exception) { return exception.toString(); }
            });
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        assertEquals(List.of(record), store.load());
        assertTrue(store.revoked().isEmpty());
        store.write(List.of(), List.of(record.id()));
        assertTrue(store.load().isEmpty());
        assertEquals(List.of(record.id()), store.revoked());
    }

    /** Separate process deliberately skips close/finally/shutdown hooks mid-transaction. */
    public static class CrashWriter {
        public static void main(String[] args) throws Exception {
            Class.forName("org.sqlite.JDBC");
            var connection = DriverManager.getConnection("jdbc:sqlite:" + args[0]);
            connection.createStatement().execute("PRAGMA cache_size = 1");
            connection.setAutoCommit(false);
            connection.createStatement().executeUpdate("DELETE FROM maces");
            var insert = connection.prepareStatement("INSERT INTO revoked_maces (id) VALUES (?)");
            insert.setString(1, args[1]);
            insert.executeUpdate();
            Runtime.getRuntime().halt(0);
        }
    }
}
