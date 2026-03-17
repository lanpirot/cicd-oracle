package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests that Java version detection selects the correct JDK for old projects.
 * The key invariant: a Java 7 project must be built with our Java 8 JDK
 * (the smallest available version >= 7), since Java 7 itself is not installed.
 */
public class JavaVersionResolverTest extends BaseTest {

    // ---- detectRequiredVersion -----------------------------------------------

    @Test
    void detectsJava7FromMavenCompilerSource(@TempDir Path dir) throws Exception {
        writePom(dir, "<maven.compiler.source>1.7</maven.compiler.source>\n"
                    + "<maven.compiler.target>1.7</maven.compiler.target>");
        assertEquals(7, JavaVersionResolver.detectRequiredVersion(dir));
    }

    @Test
    void detectsJava7ShortFormFromMavenCompilerSource(@TempDir Path dir) throws Exception {
        writePom(dir, "<maven.compiler.source>7</maven.compiler.source>");
        assertEquals(7, JavaVersionResolver.detectRequiredVersion(dir));
    }

    @Test
    void detectsJava8ViaJavaVersion(@TempDir Path dir) throws Exception {
        writePom(dir, "<java.version>1.8</java.version>");
        assertEquals(8, JavaVersionResolver.detectRequiredVersion(dir));
    }

    @Test
    void detectsJavaVersionViaReleaseTag(@TempDir Path dir) throws Exception {
        writePom(dir, "<release>11</release>");
        assertEquals(11, JavaVersionResolver.detectRequiredVersion(dir));
    }

    @Test
    void returnsZeroWhenNoPomXml(@TempDir Path dir) {
        assertEquals(0, JavaVersionResolver.detectRequiredVersion(dir));
    }

    @Test
    void returnsZeroWhenNoVersionDeclared(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project></project>");
        assertEquals(0, JavaVersionResolver.detectRequiredVersion(dir));
    }

    @Test
    void mavenCompilerSourceTakesPriorityOverJavaVersion(@TempDir Path dir) throws Exception {
        // Both tags present — maven.compiler.source wins
        writePom(dir, "<maven.compiler.source>7</maven.compiler.source>\n"
                    + "<java.version>11</java.version>");
        assertEquals(7, JavaVersionResolver.detectRequiredVersion(dir));
    }

    // ---- selectClosestVersion ------------------------------------------------

    @Test
    void java7ProjectSelectsJava8() {
        // 7 is not installed; 8 is the smallest available version >= 7
        assertFalse(AppConfig.JAVA_HOMES.containsKey(7), "Test assumes Java 7 is not in JAVA_HOMES");
        assertEquals(8, JavaVersionResolver.selectClosestVersion(7));
    }

    @Test
    void exactMatchIsPreferred() {
        // Java 11 is installed — must be selected for a Java 11 project
        assumeTrue(AppConfig.JAVA_HOMES.containsKey(11), "Java 11 not in JAVA_HOMES");
        assertEquals(11, JavaVersionResolver.selectClosestVersion(11));
    }

    @Test
    void fallsBackToHighestWhenRequiredExceedsAllInstalled() {
        int highest = AppConfig.JAVA_HOMES.keySet().stream().max(Integer::compareTo).orElseThrow();
        // Asking for a version higher than anything installed → highest wins
        assertEquals(highest, JavaVersionResolver.selectClosestVersion(highest + 100));
    }

    // ---- resolveJavaHome (end-to-end) ----------------------------------------

    @Test
    void java7ProjectResolvesToJava8Home(@TempDir Path dir) throws Exception {
        assumeTrue(AppConfig.JAVA_HOMES.get(8) != null
                && AppConfig.JAVA_HOMES.get(8).toFile().exists(),
                "Java 8 JDK not present on this machine — skipping");

        writePom(dir, "<maven.compiler.source>1.7</maven.compiler.source>");

        Optional<String> javaHome = JavaVersionResolver.resolveJavaHome(dir);

        assertTrue(javaHome.isPresent(), "Expected a Java home to be resolved for a Java 7 project");
        assertEquals(AppConfig.JAVA_HOMES.get(8).toString(), javaHome.get(),
                "Java 7 project must be built with the Java 8 JDK");
    }

    @Test
    void noJavaHomeForProjectWithoutVersionDeclaration(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project></project>");
        assertTrue(JavaVersionResolver.resolveJavaHome(dir).isEmpty());
    }

    // ---- helpers -------------------------------------------------------------

    private static void writePom(Path dir, String properties) throws Exception {
        Files.writeString(dir.resolve("pom.xml"),
                "<project><properties>\n" + properties + "\n</properties></project>");
    }
}
