package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.ConflictModuleAnalyzer.AffectedModules;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plumbing tests for selective reactor pruning. Verifies the integration points
 * between the analyzer, TestTotal, DonorTracker, command builder, and
 * VariantExecutionEngine.EngineConfig — without spawning a real Maven build.
 *
 * <p>The full integration parity test on a real multi-module repo (para a9e67cc8)
 * will land in a separate commit, after MavenExecutionFactory is wired to compute
 * affected modules per merge and pass them into EngineConfig.
 */
class SelectiveReactorPruningWiringTest extends BaseTest {

    @Test
    void engineConfig_pruningOff_whenAffectedModulesNull() {
        VariantExecutionEngine.EngineConfig cfg = new VariantExecutionEngine.EngineConfig(
                1, false, false, null, false, null, null, null, null, null, null);
        assertFalse(cfg.pruningEnabled());
    }

    @Test
    void engineConfig_pruningOff_whenAffectedAllSentinel() {
        VariantExecutionEngine.EngineConfig cfg = new VariantExecutionEngine.EngineConfig(
                1, false, false, null, false, null, null, null, null, null, null,
                AffectedModules.all());
        assertFalse(cfg.pruningEnabled());
    }

    @Test
    void engineConfig_pruningOn_whenAffectedConcrete() {
        VariantExecutionEngine.EngineConfig cfg = new VariantExecutionEngine.EngineConfig(
                1, false, false, null, false, null, null, null, null, null, null,
                AffectedModules.of(List.of("modA")));
        assertTrue(cfg.pruningEnabled());
    }

    @Test
    void buildPrunedCommand_includesAllPruningFlags() {
        String[] cmd = AppConfig.buildPrunedCommand(
                new String[]{"mvn"}, "verify", "modA,modB");
        List<String> args = List.of(cmd);
        assertTrue(args.contains("-pl"), "expected -pl flag, got " + args);
        assertTrue(args.contains("modA,modB"), "expected csv arg, got " + args);
        assertTrue(args.contains("-Dmaven.build.cache.enabled=false"),
                "expected cache-disable flag, got " + args);
        assertEquals("verify", cmd[cmd.length - 1], "goal must be last arg");
    }

    @Test
    void pruningSpec_donor_hasInstallGoalNoCsv() {
        TwoPhaseRunner.PruningSpec spec = TwoPhaseRunner.PruningSpec.donor();
        assertNull(spec.affectedModulesCsv());
        assertEquals("install", spec.goalOverride());
    }

    @Test
    void pruningSpec_prune_hasCsvNoGoalOverride() {
        TwoPhaseRunner.PruningSpec spec = TwoPhaseRunner.PruningSpec.prune("modA,modB");
        assertEquals("modA,modB", spec.affectedModulesCsv());
        assertNull(spec.goalOverride());
    }

    @Test
    void donorTracker_promotion_capturesPerModuleSnapshot() {
        DonorTracker tracker = new DonorTracker();
        TestTotal donorTt = new TestTotal();
        donorTt.setHasData(true);
        donorTt.setRunNum(10);
        donorTt.setPerModule(new java.util.LinkedHashMap<>(Map.of(
                "modA", new TestTotal.ModuleTotal(4, 0, 0, 0, 1f),
                "modB", new TestTotal.ModuleTotal(6, 0, 0, 0, 2f))));

        ch.unibe.cs.mergeci.runner.maven.CompilationResult cr =
                ch.unibe.cs.mergeci.runner.maven.CompilationResult.forTest(
                        ch.unibe.cs.mergeci.runner.maven.CompilationResult.Status.SUCCESS, List.of());
        tracker.promoteDonorIfBetter(java.nio.file.Path.of("/tmp/donor"), cr, donorTt, "1");

        Map<String, TestTotal.ModuleTotal> snapshot = tracker.getDonorPerModule();
        assertEquals(2, snapshot.size());
        assertEquals(4, snapshot.get("modA").getRunNum());
        assertEquals(6, snapshot.get("modB").getRunNum());
    }

    @Test
    void donorTracker_perModuleEmptyBeforePromotion() {
        DonorTracker tracker = new DonorTracker();
        assertTrue(tracker.getDonorPerModule().isEmpty());
    }

    @Test
    void factoryFlag_offByDefault() {
        // No System.setProperty in this test — verify the unset default.
        System.clearProperty("selectiveReactorPruning");
        assertFalse(MavenExecutionFactory.isSelectiveReactorPruningEnabled());
    }

    @Test
    void factoryFlag_onWhenPropertyTrue() {
        System.setProperty("selectiveReactorPruning", "true");
        try {
            assertTrue(MavenExecutionFactory.isSelectiveReactorPruningEnabled());
        } finally {
            System.clearProperty("selectiveReactorPruning");
        }
    }

    @Test
    void factoryFlag_offWhenPropertyExplicitFalse() {
        System.setProperty("selectiveReactorPruning", "false");
        try {
            assertFalse(MavenExecutionFactory.isSelectiveReactorPruningEnabled());
        } finally {
            System.clearProperty("selectiveReactorPruning");
        }
    }

    @AfterEach
    void cleanupProperty() {
        System.clearProperty("selectiveReactorPruning");
    }
}
