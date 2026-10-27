plugins {
    java
}

group = "com.macemanager"
version = "1.0.0"

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

dependencies {
    // Paper's API jar is the public Bukkit/Spigot API. The plugin only calls
    // types that exist on Paper, Spigot, and Bukkit.
    compileOnly("io.papermc.paper:paper-api:${paperApiVersion.get()}")
    testImplementation("io.papermc.paper:paper-api:${paperApiVersion.get()}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.15.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val pluginVersion = project.version
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    archiveFileName.set("MaceManager-${project.version}.jar")
}
