package ch.unibe.cs.mergeci.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        ObjectLoader objectLoader = git.getRepository().open(objectId);
        return objectLoader.openStream();
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
        for (File subfile : file.listFiles()) {

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
        for (String f : sourceDirectory.list()) {
            copyDirectoryCompatibityMode(new File(sourceDirectory, f), new File(destinationDirectory, f));
        }
    }

    public static void copyDirectoryCompatibityMode(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            copyDirectory(source, destination);
        } else {
            copyFile(source, destination);
        }
    }

    private static void copyFile(File sourceFile, File destinationFile)
            throws IOException {
        if (destinationFile.getParentFile() != null) destinationFile.getParentFile().mkdirs();

        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = new FileOutputStream(destinationFile)) {
            byte[] buf = new byte[1024];
            int length;
            while ((length = in.read(buf)) > 0) {
                out.write(buf, 0, length);
            }
        }catch (IOException e) {
            e.printStackTrace();
            System.out.println(sourceFile.getAbsoluteFile());
        }
    }

    public static List<Path> listFilesUsingFileWalk(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir, 100)) {
            return stream
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());
        }
    }

    private static String getFileName(String path) {
        return new File(path).getName();
    }
}
