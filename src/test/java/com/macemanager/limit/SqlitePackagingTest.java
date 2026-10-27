package com.macemanager.limit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.Properties;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class SqlitePackagingTest {
    @TempDir Path directory;

    @Test
    void distributedJarCanOpenSQLiteWithoutServerOrGradleDependencies() throws Exception {
        Path jar = Path.of(System.getProperty("macemanager.test.pluginJar"));
        try (JarFile archive = new JarFile(jar.toFile())) {
            assertNotNull(archive.getEntry("org/sqlite/JDBC.class"));
            assertNotNull(archive.getEntry("META-INF/services/java.sql.Driver"));
            assertNotNull(archive.getEntry("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE"));
            assertNotNull(archive.getEntry("META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus"));
            assertNull(archive.getEntry("org/bukkit/Bukkit.class"));
        }
        // Only JDK classes are visible to the parent. A server-provided JDBC
        // driver or logging library cannot accidentally make this test pass.
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Driver driver = (Driver) loader.loadClass("org.sqlite.JDBC").getConstructor().newInstance();
            try (var connection = driver.connect("jdbc:sqlite:" + directory.resolve("packaged.db"), new Properties());
                 var statement = connection.createStatement()) {
                assertSame(loader, connection.getClass().getClassLoader());
                statement.executeUpdate("CREATE TABLE smoke (id INTEGER PRIMARY KEY)");
                statement.executeUpdate("INSERT INTO smoke VALUES (42)");
                try (var result = statement.executeQuery("SELECT id FROM smoke")) {
                    assertTrue(result.next());
                    assertEquals(42, result.getInt(1));
                }
            }
        }
    }
}
