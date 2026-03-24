package ch.unibe.cs.mergeci.util;

import org.eclipse.jgit.lib.ProgressMonitor;

/**
 * A quiet progress monitor that suppresses verbose git clone output
 * and provides a summary at the end.
 */
public class QuietProgressMonitor implements ProgressMonitor {
    private int totalObjects = 0;
    private int totalFiles = 0;
    private String currentTask = "";

    @Override
    public void start(int totalTasks) {
        // Silent
    }

    @Override
    public void beginTask(String title, int totalWork) {
        currentTask = title;
        if (title.contains("Receiving objects")) {
            totalObjects = totalWork;
        } else if (title.contains("Checking out files")) {
            totalFiles = totalWork;
        }
    }

    @Override
    public void update(int completed) {
        // Silent - no progress updates
    }

    @Override
    public void endTask() {
        // Silent
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public void showDuration(boolean enabled) {
        // Silent - no duration output
    }

    public int getTotalObjects() {
        return totalObjects;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public String getSummary() {
        if (totalObjects > 0 && totalFiles > 0) {
            return String.format("✓ Cloned (%,d objects, %,d files)", totalObjects, totalFiles);
        } else if (totalObjects > 0) {
            return String.format("✓ Cloned (%,d objects)", totalObjects);
        }
        return "✓ Cloned";
    }
}