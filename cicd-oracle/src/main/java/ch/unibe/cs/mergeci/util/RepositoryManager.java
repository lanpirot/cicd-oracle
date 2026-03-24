package ch.unibe.cs.mergeci.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import ch.unibe.cs.mergeci.config.AppConfig;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages repository lifecycle and status tracking to avoid redundant downloads.
 */
public class RepositoryManager {
    /**
     * -- GETTER --
     *  Get the base directory where repositories are stored
     *
     * @return the base directory path
     */
    @Getter
    private final Path repoBaseDir;
    private final Map<String, RepositoryStatus> repoStatusCache;
    private final Path statusFile;
    private static final String STATUS_FILE_NAME = ".repo_status.json";

    public RepositoryManager(Path repoBaseDir) {
        this.repoBaseDir = repoBaseDir;
        this.statusFile = repoBaseDir.resolve(STATUS_FILE_NAME);
        this.repoStatusCache = loadStatusCache();
    }

    /**
     * Get the current status of a repository
     * @param repoName the repository name
     * @return the repository status, or NOT_PROCESSED if not found
     */
    public RepositoryStatus getRepositoryStatus(String repoName) {
        return repoStatusCache.getOrDefault(repoName, RepositoryStatus.NOT_PROCESSED);
    }

    /**
     * Set the status of a repository and persist the change
     * @param repoName the repository name
     * @param status the new status
     */
    public void setRepositoryStatus(String repoName, RepositoryStatus status) {
        repoStatusCache.put(repoName, status);
        saveStatusCache();
    }

    /**
     * Determine if a repository should be downloaded
     * @param repoName the repository name
     * @return true if the repository should be downloaded
     */
    public boolean shouldDownloadRepository(String repoName) {
        RepositoryStatus status = getRepositoryStatus(repoName);
        Path repoPath = repoBaseDir.resolve(repoName);

        // Download if:
        // 1. Never processed and never cloned
        // 2. Directory doesn't exist (regardless of status, except we don't re-download successful ones)
        // Note: Rejected repositories keep empty directories as markers, so no re-download needed
        return status == RepositoryStatus.NOT_PROCESSED ||
               (status != RepositoryStatus.SUCCESS && status != RepositoryStatus.NOT_PROCESSED_BUT_CLONED && !repoPath.toFile().exists());
    }

    /**
     * Get the path to a repository, downloading it if necessary
     * @param repoName the repository name
     * @param repoUrl the repository URL
     * @return the path to the repository
     * @throws IOException if the repository cannot be obtained
     */
    public Path getRepositoryPath(String repoName, String repoUrl) throws IOException {
        Path repoPath = repoBaseDir.resolve(repoName);

        if (shouldDownloadRepository(repoName)) {
            if (repoPath.toFile().exists()) {
                if (repoPath.resolve(".git").toFile().exists()) {
                    // Folder exists and is a valid git repo — treat as already cloned
                    System.out.printf("  ↩ Folder already present for %s — reusing as NOT_PROCESSED_BUT_CLONED%n", repoName);
                    setRepositoryStatus(repoName, RepositoryStatus.NOT_PROCESSED_BUT_CLONED);
                    return repoPath;
                }
                // Empty, non-git, or otherwise corrupt folder — remove and re-clone
                FileUtils.deleteDirectory(repoPath.toFile());
            }

            System.out.printf("  Cloning %s...", repoName);
            System.out.flush();

            ExecutorService cloneExecutor = Executors.newSingleThreadExecutor();
            Future<QuietProgressMonitor> future = cloneExecutor.submit(() -> GitUtils.cloneRepo(repoPath, repoUrl));
            cloneExecutor.shutdown();

            try {
                QuietProgressMonitor monitor = future.get(AppConfig.CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                System.out.println(" " + monitor.getSummary());
                setRepositoryStatus(repoName, RepositoryStatus.NOT_PROCESSED_BUT_CLONED);
                return repoPath;
            } catch (TimeoutException e) {
                future.cancel(true);
                System.out.printf(" ✗ Clone timed out after %ds%n", AppConfig.CLONE_TIMEOUT_SECONDS);
                repoPath.toFile().mkdirs();
                setRepositoryStatus(repoName, RepositoryStatus.REJECTED_CLONE_FAILED);
                throw new IOException("Clone timed out after " + AppConfig.CLONE_TIMEOUT_SECONDS + "s: " + repoUrl, e);
            } catch (ExecutionException e) {
                System.out.println(" ✗ Clone failed");
                repoPath.toFile().mkdirs();
                setRepositoryStatus(repoName, RepositoryStatus.REJECTED_CLONE_FAILED);
                throw new IOException("Failed to clone repository: " + repoUrl, e.getCause());
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("Clone interrupted: " + repoUrl, e);
            }
        }

        return repoPath;
    }

    /**
     * Mark a repository as successfully processed
     * @param repoName the repository name
     */
    public void markRepositorySuccess(String repoName) {
        setRepositoryStatus(repoName, RepositoryStatus.SUCCESS);
    }

    /**
     * Mark a repository as rejected with a specific reason
     * @param repoName the repository name
     * @param rejectionReason the reason for rejection
     */
    public void markRepositoryRejected(String repoName, RepositoryStatus rejectionReason) {
        if (!rejectionReason.isRejected()) {
            throw new IllegalArgumentException("Rejection reason must be a REJECTED_* status");
        }
        
        // Delete repository contents but keep empty directory as marker
        Path repoPath = repoBaseDir.resolve(repoName);
        if (repoPath.toFile().exists()) {
            // Delete all contents but keep the directory itself
            File[] files = repoPath.toFile().listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        FileUtils.deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        } else {
            // Create empty directory as marker if it doesn't exist
            repoPath.toFile().mkdirs();
        }
        setRepositoryStatus(repoName, rejectionReason);
    }

    /**
     * Load the status cache from disk
     * @return the loaded status cache
     */
    @SuppressWarnings("unchecked")
    private Map<String, RepositoryStatus> loadStatusCache() {
        if (!Files.exists(statusFile)) {
            return new HashMap<>();
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            // Read as map of strings, then convert to RepositoryStatus enum
            Map<String, String> stringMap = mapper.readValue(statusFile.toFile(), HashMap.class);
            Map<String, RepositoryStatus> statusMap = new HashMap<>();
            
            for (Map.Entry<String, String> entry : stringMap.entrySet()) {
                try {
                    RepositoryStatus status = RepositoryStatus.valueOf(entry.getValue());
                    statusMap.put(entry.getKey(), status);
                } catch (IllegalArgumentException e) {
                    System.err.println("Unknown repository status: " + entry.getValue() + " for repo: " + entry.getKey());
                }
            }
            
            return statusMap;
        } catch (IOException e) {
            System.err.println("Failed to load repository status cache: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Save the status cache to disk
     */
    private void saveStatusCache() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            
            // Convert enum map to string map for JSON serialization
            Map<String, String> stringMap = new HashMap<>();
            for (Map.Entry<String, RepositoryStatus> entry : repoStatusCache.entrySet()) {
                stringMap.put(entry.getKey(), entry.getValue().name());
            }
            
            Files.createDirectories(statusFile.getParent());
            mapper.writeValue(statusFile.toFile(), stringMap);
        } catch (IOException e) {
            System.err.println("Failed to save repository status cache: " + e.getMessage());
        }
    }

    /**
     * Clear the in-memory status cache and reload from disk.
     * Used when FRESH_RUN mode deletes the repository directory.
     */
    public void resetCache() {
        repoStatusCache.clear();
        repoStatusCache.putAll(loadStatusCache());
    }

    /**
     * Reset all SUCCESS repositories to NOT_PROCESSED_BUT_CLONED so they are re-analyzed
     * by the ConflictCollector and VariantRunner without re-cloning.
     * Used by REANALYZE_SUCCESS mode.
     */
    public void resetSuccessfulRepos() {
        repoStatusCache.replaceAll((name, status) ->
                status == RepositoryStatus.SUCCESS ? RepositoryStatus.NOT_PROCESSED_BUT_CLONED : status);
        saveStatusCache();
    }
}