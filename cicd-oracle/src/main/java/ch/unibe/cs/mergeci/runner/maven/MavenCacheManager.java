package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Manages Maven build cache artifacts.
 * Handles cache injection and target directory copying.
 */
public class MavenCacheManager {

    /**
     * Inject Maven cache configuration files into a project.
     *
     * @param projectDir Path to the Maven project
     */
    public void injectCacheArtifacts(Path projectDir) {
        try {
            Path mvnDir = projectDir.resolve(".mvn");

            FileUtils.copyDirectoryCompatibilityMode(
                    new File("src/main/resources/cache-artifacts/extensions.xml"),
                    mvnDir.resolve("extensions.xml").toFile());

            FileUtils.copyDirectoryCompatibilityMode(
                    new File("src/main/resources/cache-artifacts/maven-build-cache-config.xml"),
                    mvnDir.resolve("maven-build-cache-config.xml").toFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Copy target directories from source project to destination project.
     * Useful for sharing compiled artifacts between project variants.
     *
     * @param sourceProject Source project directory
     * @param destinationProject Destination project directory
     * @return true if successful, false otherwise
     */
    public boolean copyTargetDirectories(File sourceProject, File destinationProject) {
        if (!sourceProject.exists() || !sourceProject.isDirectory()) {
            System.err.println("Source project not found: " + sourceProject);
            return false;
        }

        if (!destinationProject.exists()) {
            destinationProject.mkdirs();
        }

        try (Stream<Path> walk = Files.walk(sourceProject.toPath())) {
            walk.filter(path -> path.toFile().isDirectory() &&
                            path.getFileName().toString().equals("target"))
                    .forEach(targetDir -> copyTargetDirectory(targetDir, sourceProject, destinationProject));
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        // Delete copied surefire/failsafe reports so stale results from the warmer
        // are never picked up by TestTotal when a subsequent variant's build exits early.
        deleteStaleTestReports(destinationProject.toPath());

        return true;
    }

    /**
     * Remove surefire-reports and failsafe-reports directories from all target/ dirs
     * under the given project root.  Called after copying target directories from the
     * cache warmer so that only the current variant's own test output is collected.
     */
    private void deleteStaleTestReports(Path projectRoot) {
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            walk.filter(path -> {
                        String name = path.getFileName().toString();
                        return path.toFile().isDirectory()
                                && (name.equals("surefire-reports") || name.equals("failsafe-reports"))
                                && path.getParent() != null
                                && path.getParent().getFileName().toString().equals("target");
                    })
                    .forEach(reportDir -> {
                        try {
                            org.apache.commons.io.FileUtils.deleteDirectory(reportDir.toFile());
                        } catch (IOException e) {
                            System.err.println("Warning: could not delete stale reports: " + reportDir);
                        }
                    });
        } catch (IOException e) {
            System.err.println("Warning: could not scan for stale test reports: " + e.getMessage());
        }
    }

    private void copyTargetDirectory(Path targetDir, File sourceProject, File destinationProject) {
        Path relativePath = sourceProject.toPath().relativize(targetDir);
        File destDir = destinationProject.toPath().resolve(relativePath).toFile();

        try {
            FileUtils.copyDirectoryCompatibilityMode(targetDir.toFile(), destDir);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Failed to copy " + destDir + ": " + e.getMessage());
        }
    }

    /**
     * Copy .cache directory from source to destination (for Maven build cache).
     *
     * @param sourceProject Source project directory
     * @param destinationProject Destination project directory
     */
    public void copyCacheDirectory(Path sourceProject, Path destinationProject) {
        try {
            Path sourceCacheDir = sourceProject.resolve(".cache");
            Path destCacheDir = destinationProject.resolve(".cache");

            if (sourceCacheDir.toFile().exists()) {
                FileUtils.copyDirectoryCompatibilityMode(
                        sourceCacheDir.toFile(),
                        destCacheDir.toFile());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
