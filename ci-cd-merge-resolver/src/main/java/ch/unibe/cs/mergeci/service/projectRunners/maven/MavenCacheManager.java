package ch.unibe.cs.mergeci.service.projectRunners.maven;

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

        return true;
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
