package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.RepositoryManager;
import ch.unibe.cs.mergeci.util.RepositoryStatus;
import ch.unibe.cs.mergeci.util.Utility;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public class RepoCollector {
    private static int HEADER_LINE = 1;
    private final Path cloneDir;
    private final Path tempDir;
    private final Path datasetDir;
    private final RepositoryManager repoManager;

    public RepoCollector(Path cloneDir, Path tempDir, Path datasetDir) {
        this.cloneDir = cloneDir;
        this.tempDir = tempDir;
        this.datasetDir = datasetDir;
        this.repoManager = new RepositoryManager(cloneDir);
    }

    int headerLine = 1;

    public void processExcel(Path excelFile) throws Exception {
        // Handle FRESH_RUN mode
        if (AppConfig.isFreshRun()) {
            System.out.println("FRESH_RUN enabled: Cleaning collection directories...");
            if (Files.exists(cloneDir)) {
                FileUtils.deleteDirectory(cloneDir.toFile());
            }
            if (Files.exists(datasetDir)) {
                FileUtils.deleteDirectory(datasetDir.toFile());
            }
            // Reset RepositoryManager cache after deleting directories
            repoManager.resetCache();
        }

        // Always clean temp directory
        FileUtils.deleteDirectory(tempDir.toFile());

        Set<String> seen = new HashSet<>();

        try (FileInputStream fis = new FileInputStream(excelFile.toFile());
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);

            for (Row row : sheet) {
                if (row.getRowNum() < headerLine || row.getCell(0) == null) continue;

                String repoName = receiveRepoName(row.getCell(0).getStringCellValue().trim());
                String repoUrl = row.getCell(1).getStringCellValue().trim();

                if (repoName.isEmpty() || repoUrl.isEmpty()) continue;
                if (seen.contains(repoUrl)) continue;
                seen.add(repoUrl);

                // Skip if already processed (unless FRESH_RUN)
                if (!AppConfig.isFreshRun() && Files.exists(datasetDir.resolve(repoName + AppConfig.XLSX))) {
                    System.out.println("\n\n=== Skipping repository (already processed): " + repoName + " ===");
                    continue;
                }

                System.out.println("\n\n=== Processing repository: " + repoName + " ===");

                Path repoFolder;
                try {
                    repoFolder = repoManager.getRepositoryPath(repoName, repoUrl);
                } catch (IOException e) {
                    System.out.println("Failed to clone: " + repoUrl + " - " + e.getMessage());
                    continue;
                }

                if (!isMavenProject(repoFolder)) {
                    System.out.println("Not a Maven project, skipping.");
                    repoManager.markRepositoryRejected(repoName, RepositoryStatus.REJECTED_NO_POM);
                    continue;
                }

                System.out.println("Valid Maven repository!");
                DatasetCollector collector = new DatasetCollector(
                        repoFolder,
                        tempDir.resolve(repoName),
                        AppConfig.MAX_CONFLICT_MERGES
                );

                collector.collectDataset(datasetDir.resolve(repoName + AppConfig.XLSX));
                System.out.printf("Repository %s processed successfully%n", repoFolder);
                repoManager.markRepositorySuccess(repoName);
                RepositoryCache.clear();
                WindowCache.reconfigure(new WindowCacheConfig());
                // Clean up temp files but keep the repository
                FileUtils.deleteDirectory(tempDir.resolve(repoName).toFile());
            }
        }
    }

    private boolean isMavenProject(Path repo) {
        return Files.exists(repo.resolve(AppConfig.POMXML));
    }

    private String receiveRepoName(String repoUrl) {
        return repoUrl.substring(repoUrl.lastIndexOf("/") + 1);
    }
}
