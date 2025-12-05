package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.util.FileUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class RepoCollector {
    private final Path cloneDir;
    private final Path tempDir;
    private final int start;
    private final int end;

    public RepoCollector(String cloneDir, String tempDir, int start, int end) {
        this.cloneDir = Path.of(cloneDir);
        this.tempDir = Path.of(tempDir);
        this.start = start;
        this.end = end;
    }

    public void processExcel(File excelFile) throws Exception {
        Set<String> seen = new HashSet<>();

        try (FileInputStream fis = new FileInputStream(excelFile);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);

            int count = 0;

            for (Row row : sheet) {

                String repoName = row.getCell(0).getStringCellValue().trim();
                String repoUrl = row.getCell(1).getStringCellValue().trim();

                if (repoName.isEmpty() || repoUrl.isEmpty()) continue;
                if (seen.contains(repoUrl)) continue;
                seen.add(repoUrl);

                System.out.println("\n\n=== Processing repository: " + repoName + " ===");

                File repoFolder = cloneRepo(repoName, repoUrl);

                if (repoFolder == null) {
                    System.out.println("Failed to clone: " + repoUrl);
                    continue;
                }

                if (!isMavenProject(repoFolder)) {
                    System.out.println("Not a Maven project, skipping.");
                    FileUtils.deleteDirectory(repoFolder);
                    continue;
                }

                System.out.println("Valid Maven repository!");

                DatasetCollector collector = new DatasetCollector(
                        repoFolder.getAbsolutePath(),
                        tempDir.resolve(repoName).toString(),
                        200
                );

                count++;
            }
        }
    }

    private File cloneRepo(String repoName, String url) {

        Path target = cloneDir.resolve(repoName);
        // then clone
        System.out.printf("Cloning from %s to %s %n", url, repoName);
        try (Git result = Git.cloneRepository()
                .setURI(url)
                .setDirectory(target.toFile())
                .setProgressMonitor(new SimpleProgressMonitor())
                .call()) {
            // Note: the call() returns an opened repository already which needs to be closed to avoid file handle leaks!
            System.out.println("Having repository: " + result.getRepository().getDirectory());
        } catch (InvalidRemoteException e) {
            throw new RuntimeException(e);
        } catch (TransportException e) {
            throw new RuntimeException(e);
        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }

        return target.toFile();
    }

    private boolean isMavenProject(File repo) {
        return Files.exists(repo.toPath().resolve("pom.xml"));
    }
}
