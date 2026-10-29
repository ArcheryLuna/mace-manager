plugins {
    java
}

group = "com.macemanager"
version = "1.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Override for compatibility checks without raising the default server target.
val paperApiVersion = providers.gradleProperty("paperApiVersion").orElse("1.21.4-R0.1-SNAPSHOT")
val sqliteDriver by configurations.creating
configurations.implementation.get().extendsFrom(sqliteDriver)

dependencies {
    sqliteDriver("org.xerial:sqlite-jdbc:3.53.4.0")
    // Paper's API jar is the public Bukkit/Spigot API. The plugin only calls
    // types that exist on Paper, Spigot, and Bukkit.
    compileOnly("io.papermc.paper:paper-api:${paperApiVersion.get()}")
    testImplementation("io.papermc.paper:paper-api:${paperApiVersion.get()}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.15.2")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    systemProperty("macemanager.test.pluginJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}

tasks.processResources {
    val pluginVersion = project.version
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    archiveFileName.set("MaceManager-${project.version}.jar")
    // Ship the JDBC driver and its native libraries; server library loaders vary.
    from(sqliteDriver.map { zipTree(it) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA",
                "module-info.class", "META-INF/versions/**/module-info.class")
    }
    manifest.attributes["Multi-Release"] = "true"
}
