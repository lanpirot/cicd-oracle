package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class TestTotalTest extends BaseTest {

    @Test
    void outputResult() throws IOException {
        TestTotal testTotal = new TestTotal(AppConfig.TEST_REPO_DIR.resolve(AppConfig.jitwatch).toFile());
        System.out.println(testTotal);
        assertNotNull(testTotal, "TestTotal should not be null for jitwatch");

        testTotal = new TestTotal(AppConfig.TEST_REPO_DIR.resolve(AppConfig.zembereknlp).toFile());
        System.out.println(testTotal);
        assertNotNull(testTotal, "TestTotal should not be null for zembereknlp");

        testTotal = new TestTotal(AppConfig.TEST_REPO_DIR.resolve(AppConfig.jacksonDatabind).toFile());
        System.out.println(testTotal);
        assertNotNull(testTotal, "TestTotal should not be null for jacksonDatabind");
    }

    @Test
    void copyOf_createsIndependentEqualInstance() {
        TestTotal source = new TestTotal();
        source.setRunNum(42);
        source.setFailuresNum(3);
        source.setErrorsNum(1);
        source.setSkippedNum(2);
        source.setElapsedTime(7.5f);
        source.setHasData(true);

        TestTotal copy = TestTotal.copyOf(source);

        assertEquals(42, copy.getRunNum());
        assertEquals(3,  copy.getFailuresNum());
        assertEquals(1,  copy.getErrorsNum());
        assertEquals(2,  copy.getSkippedNum());
        assertEquals(7.5f, copy.getElapsedTime(), 0.0001f);
        assertTrue(copy.isHasData());

        // Mutating the copy must not bleed into the source — important when one
        // donor's TestTotal is inherited by many cache-hit variants.
        copy.setRunNum(100);
        assertEquals(42, source.getRunNum());
    }
}
