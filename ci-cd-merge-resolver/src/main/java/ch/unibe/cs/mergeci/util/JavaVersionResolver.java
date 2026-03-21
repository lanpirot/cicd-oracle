package ch.unibe.cs.mergeci.util;

import ch.unibe.cs.mergeci.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Java version requirements from pom.xml and maps them to installed JDKs.
 */
public class JavaVersionResolver {

    private static final List<Integer> AVAILABLE_VERSIONS = AppConfig.JAVA_HOMES.keySet()
            .stream().sorted().toList();

    // pom.xml detection patterns (ordered by preference).
    // Each pattern captures either a literal version number or a ${property} reference.
    private static final List<Pattern> POM_PATTERNS = List.of(
            Pattern.compile("<maven\\.compiler\\.release>\\s*([\\d.${}a-zA-Z._-]+)\\s*</maven\\.compiler\\.release>"),
            Pattern.compile("<maven\\.compiler\\.source>\\s*([\\d.${}a-zA-Z._-]+)\\s*</maven\\.compiler\\.source>"),
            Pattern.compile("<java\\.version>\\s*([\\d.${}a-zA-Z._-]+)\\s*</java\\.version>"),
            Pattern.compile("<release>\\s*(\\d+)\\s*</release>"),
            Pattern.compile("<jdk-version>\\s*([\\d.${}a-zA-Z._-]+)\\s*</jdk-version>"),
            // Fallback: bare <source>N</source> inside maven-compiler-plugin config blocks.
            // Only matches numeric values (paths like src/main/java won't match [\\d.]+).
            Pattern.compile("<source>\\s*([\\d.]+)\\s*</source>")
    );

    // Matches a Maven property reference like ${some.property}
    private static final Pattern PROPERTY_REF = Pattern.compile("^\\$\\{([^}]+)}$");

    // Maven log error patterns indicating a Java version mismatch
    private static final List<Pattern> LOG_ERROR_PATTERNS = List.of(
            Pattern.compile("error: invalid source release: ([\\d.]+)"),
            Pattern.compile("invalid target release: ([\\d.]+)"),
            Pattern.compile("release version (\\d+) not supported"),
            Pattern.compile("source release (\\d+) is no longer supported"),
            Pattern.compile("[Ss]ource option (\\d+) is no longer supported")
    );

    // Plugin compatibility errors that are resolved by switching to an older JDK.
    // - gmaven-plugin:1.5 (and similar old Groovy-based plugins) crash on Java 9+ with
    //   ExceptionInInitializerError due to Groovy's use of internal JDK APIs.
    // - spotless / google-java-format 1.x access internal JDK compiler APIs (e.g.
    //   com.sun.tools.javac.util) that the Java 9+ module system no longer exports to
    //   unnamed modules. Java 8 has no module system, so the access is permitted there.
    private static final Pattern PLUGIN_COMPAT_PATTERN =
            Pattern.compile("ExceptionInInitializerError|does not export .+ to unnamed module");

    /**
     * Returns the JAVA_HOME for the closest installed JDK that satisfies the project's
     * Java version requirement as declared in pom.xml. Empty if not detectable or already satisfied.
     */
    public static Optional<String> resolveJavaHome(Path projectPath) {
        int required = detectRequiredVersion(projectPath);
        if (required <= 0) return Optional.empty();
        int selected = selectClosestVersion(required);
        Path javaHome = AppConfig.JAVA_HOMES.get(selected);
        if (javaHome == null || !javaHome.toFile().exists()) return Optional.empty();
        return Optional.of(javaHome.toString());
    }

    /**
     * Parses pom.xml for the declared Java version. Returns 0 if not found.
     * Resolves one level of Maven property interpolation (e.g. ${java.release} → 11).
     */
    public static int detectRequiredVersion(Path projectPath) {
        Path pomFile = projectPath.resolve("pom.xml");
        if (!pomFile.toFile().exists()) return 0;
        try {
            String pom = Files.readString(pomFile);
            for (Pattern p : POM_PATTERNS) {
                Matcher m = p.matcher(pom);
                if (!m.find()) continue;
                String value = m.group(1).trim();
                Matcher ref = PROPERTY_REF.matcher(value);
                if (ref.matches()) {
                    value = resolveProperty(pom, ref.group(1));
                    if (value == null) continue;
                }
                try {
                    return parseVersion(value);
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException ignored) {}
        return 0;
    }

    /**
     * Looks up a Maven property by name in the pom's {@code <properties>} block.
     * Returns null if not found.
     */
    private static String resolveProperty(String pom, String propertyName) {
        String tag = Pattern.quote(propertyName);
        Pattern p = Pattern.compile("<" + tag + ">\\s*([\\d.]+)\\s*</" + tag + ">");
        Matcher m = p.matcher(pom);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Scans a Maven log file for Java version error messages.
     * Returns the required version mentioned in the error, or 0 if none found.
     */
    public static int detectVersionErrorInLog(Path logFile) {
        if (logFile == null || !logFile.toFile().exists()) return 0;
        try {
            String log = Files.readString(logFile);
            for (Pattern p : LOG_ERROR_PATTERNS) {
                Matcher m = p.matcher(log);
                if (m.find()) return parseVersion(m.group(1));
            }
        } catch (IOException | NumberFormatException ignored) {}
        return 0;
    }

    /**
     * Returns true if the build log contains a plugin compatibility error that is
     * typically resolved by switching to an older JDK (e.g. Java 8).
     * Example: gmaven-plugin:1.5 crashes with ExceptionInInitializerError on Java 9+.
     */
    public static boolean detectPluginCompatibilityError(Path logFile) {
        if (logFile == null || !logFile.toFile().exists()) return false;
        try {
            String log = Files.readString(logFile);
            return PLUGIN_COMPAT_PATTERN.matcher(log).find();
        } catch (IOException ignored) {}
        return false;
    }

    /**
     * Returns the smallest available Java version >= required.
     * Falls back to the highest installed version if none qualifies.
     */
    public static int selectClosestVersion(int required) {
        for (int v : AVAILABLE_VERSIONS) {
            if (v >= required) return v;
        }
        return AVAILABLE_VERSIONS.get(AVAILABLE_VERSIONS.size() - 1);
    }

    /** Returns the highest available Java version number. */
    public static int selectHighestVersion() {
        return AVAILABLE_VERSIONS.isEmpty() ? 0 : AVAILABLE_VERSIONS.get(AVAILABLE_VERSIONS.size() - 1);
    }

    /**
     * Same as {@link #resolveJavaHome(Path)} but takes an explicit version number
     * rather than reading from pom.xml. Useful for retry logic after log-based detection.
     */
    public static Optional<String> resolveJavaHomeForVersion(int required) {
        if (required <= 0) return Optional.empty();
        int selected = selectClosestVersion(required);
        Path javaHome = AppConfig.JAVA_HOMES.get(selected);
        if (javaHome == null || !javaHome.toFile().exists()) return Optional.empty();
        return Optional.of(javaHome.toString());
    }

    /**
     * Returns the JAVA_HOME for the highest installed JDK.
     * Used when a project's {@code .mvn/jvm.config} contains flags that require a newer JVM
     * than the compilation target (e.g. {@code --sun-misc-unsafe-memory-access=allow}).
     */
    public static Optional<String> resolveHighestJavaHome() {
        int highest = selectHighestVersion();
        if (highest == 0) return Optional.empty();
        Path javaHome = AppConfig.JAVA_HOMES.get(highest);
        if (javaHome == null || !javaHome.toFile().exists()) return Optional.empty();
        return Optional.of(javaHome.toString());
    }

    /**
     * Returns true if the project has a {@code .mvn/jvm.config} file.
     * Such projects may require a newer JVM to run Maven itself than their compilation target suggests.
     */
    public static boolean hasMvnJvmConfig(Path projectPath) {
        return projectPath.resolve(".mvn/jvm.config").toFile().exists();
    }

    private static int parseVersion(String raw) {
        // Normalise "1.8" → 8
        if (raw.startsWith("1.")) raw = raw.substring(2);
        String major = raw.contains(".") ? raw.split("\\.")[0] : raw;
        return Integer.parseInt(major);
    }
}
