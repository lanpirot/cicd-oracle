package ch.unibe.cs.mergeci.util;

import lombok.Getter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class Utility {

    @Getter
    public enum MERGECOLUMN {
        mergeCommit(0, "Merge Commit"),
        parent1(1, "Parent1"),
        parent2(2, "Parent2"),
        numTests(3, "Number of Tests"),
        numConflictingFiles(4, "Number of Conflicting Files"),
        numJavaFiles(5, "Number of Java Files"),
        compilationSuccess(6, "Compilation Success"),
        testSuccess(7, "Test Success"),
        elapsedTestTime(8, "Elapsed Time"),
        isMultiModule(9, "Multi Module");

        private final int columnNumber;
        private final String columnName;

        MERGECOLUMN(int columnNumber, String columnName){
            this.columnNumber = columnNumber;
            this.columnName = columnName;
        }
    }

    @Getter
    public enum PROJECTCOLUMN {
        repoName(0, "Repository Name"),
        repoURL(1, "Repository URL");

        private final int columnNumber;
        private final String columnName;

        PROJECTCOLUMN(int columnNumber, String columnName){
            this.columnNumber = columnNumber;
            this.columnName = columnName;
        }
    }

    @Getter
    public enum Experiments {
        no_cache_no_parallel(false, false, "no_optimization"),
        //cache_no_parallel(true, false),       //doesn't really make sense
        no_cache_parallel(false, true, "parallel"),
        cache_parallel(true, true, "cache_parallel");

        private final boolean cache;
        private final boolean parallel;
        private final String name;

        Experiments(boolean cache, boolean parallel, String name){
            this.cache = cache;
            this.parallel = parallel;
            this.name = name;
        }
    }

    public static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!pool.awaitTermination(1, TimeUnit.HOURS)) {
                pool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!pool.awaitTermination(60, TimeUnit.SECONDS))
                    System.err.println("Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            pool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }
}
