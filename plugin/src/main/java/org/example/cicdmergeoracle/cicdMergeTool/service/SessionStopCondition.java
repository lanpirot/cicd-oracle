package org.example.cicdmergeoracle.cicdMergeTool.service;

import ch.unibe.cs.mergeci.runner.VariantStopCondition;

/**
 * Adapts {@link OracleSession}'s pause/resume/cancel state to the
 * {@link VariantStopCondition} interface expected by the shared engine.
 */
class SessionStopCondition implements VariantStopCondition {
    private static final int MAVEN_TIMEOUT_SECONDS = 600;
    private final OracleSession session;

    SessionStopCondition(OracleSession session) {
        this.session = session;
    }

    @Override
    public boolean isCancelled() {
        return session.isCancelled();
    }

    @Override
    public void waitIfPaused() throws InterruptedException {
        session.waitIfPaused();
    }

    @Override
    public boolean isStopped() {
        return session.isStopped();
    }

    @Override
    public int remainingSeconds() {
        // Plugin uses a fixed per-variant timeout (no global deadline).
        // Return -1 so the engine uses its 600s default.
        return -1;
    }
}
