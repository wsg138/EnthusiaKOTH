plugins {
    kotlin("jvm") version "2.1.0"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "net.badgersmc.ek"
version = "0.2.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // Nexus — core + i18n + loader
    implementation("com.github.BadgersMC.Nexus:nexus-core:v2.1.1")
    implementation("com.github.BadgersMC.Nexus:nexus-i18n:v2.1.1")
    implementation("com.github.BadgersMC.Nexus:nexus-paper-loader:v2.1.1")

    // LumaGuilds API (provided by server, join-classpath)
    // Path can be overridden via -Plumaguilds.jar=... or LUMAGUILDS_JAR env var
    val lumaguildsJar = System.getenv("LUMAGUILDS_JAR")
        ?: project.findProperty("lumaguilds.jar")?.toString()
        ?: findProperty("defaultLumaguildsJar")?.toString()
        ?: "/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar"
    compileOnly(files(lumaguildsJar))

    // PlaceholderAPI (provided by server)
    compileOnly("me.clip:placeholderapi:2.11.6")

    // Runtime deps — shaded into the jar (Leaf blocks Maven Central at startup)
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.slf4j:slf4j-nop:2.0.13")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.13")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.jar {
    archiveBaseName.set("EnthusiaKOTH")
}

tasks.shadowJar {
    archiveBaseName.set("EnthusiaKOTH")
    mergeServiceFiles()
    // No excludes — shade everything (Leaf blocks all runtime Maven resolution)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
