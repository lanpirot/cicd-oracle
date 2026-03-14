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

    // pom.xml detection patterns (ordered by preference)
    private static final List<Pattern> POM_PATTERNS = List.of(
            Pattern.compile("<maven\\.compiler\\.source>\\s*([\\d.]+)\\s*</maven\\.compiler\\.source>"),
            Pattern.compile("<java\\.version>\\s*([\\d.]+)\\s*</java\\.version>"),
            Pattern.compile("<release>\\s*(\\d+)\\s*</release>")
    );

    // Maven log error patterns indicating a Java version mismatch
    private static final List<Pattern> LOG_ERROR_PATTERNS = List.of(
            Pattern.compile("error: invalid source release: ([\\d.]+)"),
            Pattern.compile("release version (\\d+) not supported"),
            Pattern.compile("source release (\\d+) is no longer supported")
    );

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
     */
    public static int detectRequiredVersion(Path projectPath) {
        Path pomFile = projectPath.resolve("pom.xml");
        if (!pomFile.toFile().exists()) return 0;
        try {
            String pom = Files.readString(pomFile);
            for (Pattern p : POM_PATTERNS) {
                Matcher m = p.matcher(pom);
                if (m.find()) return parseVersion(m.group(1));
            }
        } catch (IOException | NumberFormatException ignored) {}
        return 0;
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
     * Returns the smallest available Java version >= required.
     * Falls back to the highest installed version if none qualifies.
     */
    public static int selectClosestVersion(int required) {
        for (int v : AVAILABLE_VERSIONS) {
            if (v >= required) return v;
        }
        return AVAILABLE_VERSIONS.get(AVAILABLE_VERSIONS.size() - 1);
    }

    private static int parseVersion(String raw) {
        // Normalise "1.8" → 8
        if (raw.startsWith("1.")) raw = raw.substring(2);
        String major = raw.contains(".") ? raw.split("\\.")[0] : raw;
        return Integer.parseInt(major);
    }
}
