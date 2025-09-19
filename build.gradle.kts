plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.loliwolf.gostructcopy"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://cache-redirector.jetbrains.com/intellij-repository/releases")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-releases")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

intellij {
    version.set("2024.1.2")
    type.set("GO")
    plugins.set(listOf("org.jetbrains.plugins.go"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

@Suppress("UnstableApiUsage")
tasks.withType<Test>().configureEach {
    systemProperty("idea.platform.prefix", "GoLand")
}

// Keep plugin.xml as-is; skip automatic patching.
tasks.named("patchPluginXml").configure {
    enabled = false
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
}


