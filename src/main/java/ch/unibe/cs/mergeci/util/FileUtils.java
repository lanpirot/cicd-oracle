package ch.unibe.cs.mergeci.util;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;

public class FileUtils {
    private static void saveFilesFromObjectId(String projectRoot, Map<String, ObjectId> files){
        
    }

    public void saveFile(String path, ObjectStream objectStream) throws IOException {
        File file = new File(path);
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        file.createNewFile();
        try (FileOutputStream objectOutputStream = new FileOutputStream(file); objectStream) {
            while (objectStream.available() > 0) {
                objectOutputStream.write(objectStream.readAllBytes());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
