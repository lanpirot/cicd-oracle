package ch.unibe.cs.mergeci.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileUtils {
    public static void saveFilesFromObjectId(Path projectRoot, Map<String, ObjectId> files, Git git) throws IOException {
        for (Map.Entry<String, ObjectId> entry : files.entrySet()) {
            ObjectStream objectStream = getFileFromObject(entry.getValue(), git);
            saveFile(projectRoot.resolve(entry.getKey()).toFile(), objectStream);
        }
    }

    public static void saveFile(File file, ObjectStream objectStream) throws IOException {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        file.createNewFile();
        try (OutputStream objectOutputStream = new FileOutputStream(file); objectStream) {
            while (objectStream.available() > 0) {
                objectOutputStream.write(objectStream.readAllBytes());
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw(e);
        }
    }


    public static ObjectStream getFileFromObject(ObjectId objectId, Git git) throws IOException {
        try {
            ObjectLoader objectLoader = git.getRepository().open(objectId);
            return objectLoader.openStream();
        } catch (IOException e) {
            System.err.println("Failed to load object " + objectId.name() + ": " + e.getMessage());
            throw e;  // Re-throw to let caller handle
        } catch (Exception e) {
            System.err.println("Unexpected error loading object " + objectId.name() + ": " + e.getMessage());
            throw new IOException("Failed to load git object", e);
        }
    }

    public static Map<String, String> objectIdToStringMap(Map<String, ObjectId> map, Git git) throws IOException {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, ObjectId> entry : map.entrySet()) {
            String key = entry.getKey();
            ObjectId objectId = entry.getValue();

            try (ObjectStream objectStream = getFileFromObject(objectId, git)) {
                while (objectStream.available() > 0) {
                    result.put(key, objectStream.readAllBytes().toString());
                }

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

    // function to delete subdirectories and files
    public static void deleteDirectory(File file) {
        // store all the paths of files and folders present
        // inside directory
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children == null) return;
        for (File subfile : children) {

            // if it is a subfolder,e.g Rohan and Ritik,
            //  recursively call function to empty subfolder
            if (subfile.isDirectory()) {
                deleteDirectory(subfile);
            }

            // delete files and empty subfolders
            subfile.delete();
        }

        file.delete();
    }

    public static void printErrorMessage(Process pr) {
        try (InputStream errorStream = pr.getErrorStream(); InputStream messageStream = pr.getInputStream()) {
            System.out.println(new String(errorStream.readAllBytes()));
            System.out.println(new String(messageStream.readAllBytes()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void copyDirectory(File sourceDirectory, File destinationDirectory) throws IOException {
        if (!destinationDirectory.exists()) {
            destinationDirectory.mkdir();
        }
        String[] entries = sourceDirectory.list();
        if (entries == null) return;
        for (String f : entries) {
            copyDirectoryCompatibilityMode(new File(sourceDirectory, f), new File(destinationDirectory, f));
        }
    }

    public static void copyDirectoryCompatibilityMode(File source, File destination) throws IOException {
        Path sourcePath = source.toPath();
        if (Files.isSymbolicLink(sourcePath)) {
            Path linkTarget = Files.readSymbolicLink(sourcePath);
            Files.createSymbolicLink(destination.toPath(), linkTarget);
            return;
        }
        if (source.isDirectory()) {
            copyDirectory(source, destination);
        } else {
            copyFile(source, destination);
        }
    }

    private static void copyFile(File sourceFile, File destinationFile)
            throws IOException {
        if (destinationFile.getParentFile() != null) destinationFile.getParentFile().mkdirs();
        Files.copy(sourceFile.toPath(), destinationFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Finds every {@code pom.xml} under {@code projectDir} and replaces any hardcoded
     * {@code <skipTests>true</skipTests>} with {@code <skipTests>false</skipTests>}.
     *
     * <p>The Maven Surefire {@code skipTests} configuration element is <em>not</em>
     * overridable via {@code -DskipTests=false} when it is hardcoded inside the plugin's
     * {@code <configuration>} block (unlike when it is declared in {@code <properties>}).
     * Patching the file directly is the only reliable way to force tests to run.
     */
    public static void enableTestsInAllPoms(Path projectDir) {
        try {
            Files.walkFileTree(projectDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if ("pom.xml".equals(String.valueOf(file.getFileName()))) {
                        patchSkipTestsInPom(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Warning: failed to walk " + projectDir + " for pom.xml patching: " + e.getMessage());
        }
    }

    private static void patchSkipTestsInPom(Path pomFile) {
        try {
            String content = new String(Files.readAllBytes(pomFile), StandardCharsets.UTF_8);
            String patched = content.replaceAll(
                    "(?i)<skipTests>\\s*true\\s*</skipTests>",
                    "<skipTests>false</skipTests>");
            if (!patched.equals(content)) {
                Files.write(pomFile, patched.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            System.err.println("Warning: failed to patch skipTests in " + pomFile + ": " + e.getMessage());
        }
    }

    /**
     * List every regular file under {@code dir}, tolerant of per-path I/O failures.
     *
     * <p>The variant working tree lives on a fuse-overlayfs mount; when something
     * (e.g. {@code wipeTargetDirsInUpper}) deletes from the upper layer directly,
     * the merged view's dirent cache can briefly list a child whose inode is gone.
     * A naive {@code Files.walk} aborts the whole stream on the resulting
     * {@code NoSuchFileException}; here we skip that one entry and keep walking.
     */
    public static List<Path> listFilesUsingFileWalk(Path dir) throws IOException {
        if (!Files.exists(dir)) throw new java.nio.file.NoSuchFileException(dir.toString());
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) out.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static String getFileName(String path) {
        return new File(path).getName();
    }
}
