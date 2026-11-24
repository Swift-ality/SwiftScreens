plugins {
    java
    `maven-publish`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.22"
}

group = "com.swiftality"
version = "0.3.0"
description = "Fullscreen clickable menus for vanilla Minecraft clients, drawn with Java2D on maps and core shaders."

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Server internals (Mojang names) for the packet-only screens; 26.x runs Mojang names, so no reobf.
    paperweight.paperDevBundle("26.3.build.249-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    processResources {
        inputs.property("version", project.version)
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
}

// Plugins build against this with compileOnly("com.swiftality:swiftscreens:<version>") (mavenLocal or JitPack).
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "swiftscreens"
            from(components["java"])
        }
    }
}
