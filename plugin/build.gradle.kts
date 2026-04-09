plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2025.1")
    type.set("IC") // Target IDE Platform

    plugins.set(listOf("Git4Idea", "JUnit"))
}
java {
}
tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("251")
        untilBuild.set("")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    buildSearchableOptions {
        enabled = false
    }

    test {
        useJUnitPlatform()
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")

    testImplementation("org.junit.platform:junit-platform-launcher:1.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-engine:1.10.0")

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    testCompileOnly("org.projectlombok:lombok:1.18.42")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.42")

    // cicd-oracle pipeline (provides model, patterns, runner, scoring)
    implementation("ch.unibe.cs:cicd-oracle:0.0.1-SNAPSHOT") {
        exclude(group = "org.springframework.boot")
        exclude(group = "org.springframework")
    }

    // JGit (also transitive from cicd-oracle, but pinned for IntelliJ compatibility)
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")

    // Logging
    implementation("org.slf4j:slf4j-simple:1.7.36")
}