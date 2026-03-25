plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set("2023.2.6")
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
        sinceBuild.set("223")
//        untilBuild.set("253.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
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

    // JGit
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")

    // Maven
    implementation("org.apache.maven:maven-embedder:3.9.11")
    implementation("org.apache.maven:maven-compat:3.9.11")

    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.18")

    // Logging
    implementation("org.slf4j:slf4j-simple:1.7.36")

    // Commons
    implementation("org.apache.commons:commons-lang3:3.12.0")

    // Excel
    implementation("org.apache.poi:poi:5.5.0")
    implementation("org.apache.poi:poi-ooxml:5.5.0")
    implementation("org.jxls:jxls-jexcel:1.0.9")
    implementation("org.dhatim:fastexcel-reader:0.19.0")
    implementation("org.dhatim:fastexcel:0.19.0")

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // JUnit Platform
    testImplementation("org.junit.platform:junit-platform-launcher:1.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-engine:1.10.0")
}