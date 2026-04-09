package ch.unibe.cs.mergeci.plugin;

import ch.unibe.cs.mergeci.present.VariantScore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the OracleSession concept: thread-safe best-variant tracking
 * and history accumulation across runs.
 */
class OracleSessionTest {

    /** Minimal session for testing (mirrors plugin's OracleSession). */
    static class OracleSession {
        volatile VariantScore currentBest;
        final CopyOnWriteArrayList<VariantScore> history = new CopyOnWriteArrayList<>();

        synchronized boolean addResult(VariantScore score) {
            history.add(score);
            if (score == null) return false;
            if (currentBest == null || score.isBetterThan(currentBest)) {
                currentBest = score;
                return true;
            }
            return false;
        }

        void prepareRestart() {
            currentBest = null;
        }
    }

    private static VariantScore score(int modules, int tests) {
        return new VariantScore(modules, tests, 0.0, Integer.MAX_VALUE);
    }

    @Test
    void currentBestUpdatesOnBetterScore() {
        var session = new OracleSession();
        assertTrue(session.addResult(score(2, 5)));
        assertTrue(session.addResult(score(3, 10)));
        assertEquals(3, session.currentBest.successfulModules());
    }

    @Test
    void currentBestDoesNotDowngrade() {
        var session = new OracleSession();
        session.addResult(score(5, 20));
        assertFalse(session.addResult(score(3, 10)));
        assertEquals(5, session.currentBest.successfulModules());
    }

    @Test
    void nullScoreDoesNotUpdateBest() {
        var session = new OracleSession();
        assertFalse(session.addResult(null));
        assertNull(session.currentBest);
    }

    @Test
    void historyPreservedAcrossRestart() {
        var session = new OracleSession();
        session.addResult(score(1, 1));
        session.prepareRestart();

        assertNull(session.currentBest);
        assertEquals(1, session.history.size());

        session.addResult(score(2, 2));
        assertEquals(2, session.history.size());
    }

    @Test
    void concurrentAddDoesNotLoseEntries() throws Exception {
        var session = new OracleSession();
        int threads = 8, perThread = 100;
        var latch = new CountDownLatch(threads);
        var exec = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            exec.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    session.addResult(score(1, 1));
                }
                latch.countDown();
            });
        }

        latch.await();
        exec.shutdown();
        assertEquals(threads * perThread, session.history.size());
    }

    @Test
    void equalScoreDoesNotReplaceBest() {
        var session = new OracleSession();
        var first = score(3, 10);
        var equal = score(3, 10);
        session.addResult(first);
        assertFalse(session.addResult(equal));
        assertSame(first, session.currentBest);
    }

    @Test
    void bestTracksSecondaryKeyTests() {
        var session = new OracleSession();
        session.addResult(score(3, 5));
        assertTrue(session.addResult(score(3, 10)));  // same modules, more tests
        assertEquals(10, session.currentBest.passedTests());
    }

    @Test
    void multipleRestartsPreserveFullHistory() {
        var session = new OracleSession();
        session.addResult(score(1, 1));
        session.prepareRestart();
        session.addResult(score(2, 2));
        session.prepareRestart();
        session.addResult(score(3, 3));

        assertEquals(3, session.history.size());
        // Last addResult after last prepareRestart set the best
        assertEquals(3, session.currentBest.successfulModules());
    }

    @Test
    void bestAfterRestartIsFromNewRunOnly() {
        var session = new OracleSession();
        session.addResult(score(10, 50));  // very good
        assertEquals(10, session.currentBest.successfulModules());

        session.prepareRestart();
        assertNull(session.currentBest);

        session.addResult(score(1, 1));  // worse than old, but best of new run
        assertEquals(1, session.currentBest.successfulModules());
    }

    @Test
    void concurrentAddPreservesBestInvariant() throws Exception {
        var session = new OracleSession();
        int threads = 4, perThread = 200;
        var latch = new CountDownLatch(threads);
        var exec = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            int threadId = t;
            exec.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    session.addResult(score(threadId, i));
                }
                latch.countDown();
            });
        }

        latch.await();
        exec.shutdown();

        // Best should be the highest modules (thread 3), highest tests (199)
        assertEquals(3, session.currentBest.successfulModules());
        assertEquals(199, session.currentBest.passedTests());
    }
}
