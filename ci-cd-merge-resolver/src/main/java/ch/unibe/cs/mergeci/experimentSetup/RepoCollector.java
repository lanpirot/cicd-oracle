package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.FileUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class RepoCollector {
    private static int HEADER_LINE = 1;
    private final Path cloneDir;
    private final Path tempDir;
    private final Path datasetDir;

    public RepoCollector(String cloneDir, String tempDir) {
        Path repoCollector = Path.of("repoCollector");
        this.cloneDir = repoCollector.resolve(cloneDir);
        this.tempDir = repoCollector.resolve(tempDir);
        this.datasetDir = repoCollector.resolve("datasets");
    }

    int headerLine = 1;

    public void processExcel(File excelFile) throws Exception {
        FileUtils.deleteDirectory(cloneDir.toFile());
        FileUtils.deleteDirectory(tempDir.toFile());

        Set<String> seen = new HashSet<>();

        try (FileInputStream fis = new FileInputStream(excelFile);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);

            for (Row row : sheet) {
                if (row.getRowNum() < headerLine) continue;

                String repoName = receiveRepoName(row.getCell(0).getStringCellValue().trim());
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
                        repoFolder.getPath(),
                        tempDir.resolve(repoName).toString(),
                        AppConfig.MAX_CONFLICT_MERGES
                );

                collector.collectDataset(datasetDir.resolve(repoName + ".xlsx").toFile());
                System.out.printf("Deleting repository %s%n", repoFolder);
                RepositoryCache.clear();
                WindowCache.reconfigure(new WindowCacheConfig());
                FileUtils.deleteDirectory(repoFolder);
                FileUtils.deleteDirectory(tempDir.toFile());
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

        } catch (GitAPIException e) {
            throw new RuntimeException(e);
        }
        RepositoryCache.clear();
        WindowCache.reconfigure(new WindowCacheConfig());

        return target.toFile();
    }

    private boolean isMavenProject(File repo) {
        return Files.exists(repo.toPath().resolve("pom.xml"));
    }

    private String receiveRepoName(String repoUrl) {
        return repoUrl.substring(repoUrl.lastIndexOf("/") + 1);
    }
}
