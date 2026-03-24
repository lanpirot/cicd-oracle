package ch.unibe.cs.mergeci;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CiCdMergeResolverApplication - validates application class structure.
 */
public class CiCdMergeResolverApplicationTest extends BaseTest {

    @Test
    void testApplicationMainClassExists() {
        assertDoesNotThrow(() -> Class.forName("ch.unibe.cs.mergeci.CiCdMergeResolverApplication"),
                "CiCdMergeResolverApplication class should exist");
    }

    @Test
    void testApplicationMainMethodExists() throws NoSuchMethodException {
        Class<?> appClass = CiCdMergeResolverApplication.class;
        assertNotNull(appClass.getDeclaredMethod("main", String[].class),
                "Application should have main(String[] args) method");
    }

    @Test
    void testApplicationMainMethodIsPublicStatic() throws NoSuchMethodException {
        Class<?> appClass = CiCdMergeResolverApplication.class;
        var mainMethod = appClass.getDeclaredMethod("main", String[].class);

        assertTrue(java.lang.reflect.Modifier.isPublic(mainMethod.getModifiers()),
                "main method should be public");
        assertTrue(java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers()),
                "main method should be static");
    }
}
