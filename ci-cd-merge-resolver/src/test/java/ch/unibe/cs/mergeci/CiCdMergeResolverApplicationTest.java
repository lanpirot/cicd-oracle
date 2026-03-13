package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.BaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CiCdMergeResolverApplication - validates Spring Boot application setup.
 */
@SpringBootTest(classes = CiCdMergeResolverApplication.class, properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "spring.main.web-application-type=none"
})
@ActiveProfiles("test")
public class CiCdMergeResolverApplicationTest extends BaseTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void testApplicationContextLoads() {
        assertNotNull(applicationContext, "Application context should be loaded");
    }

    @Test
    void testApplicationContextHasBeans() {
        assertTrue(applicationContext.getBeanDefinitionCount() > 0,
                "Application context should have beans loaded");
    }

    @Test
    void testApplicationMainClassExists() {
        assertDoesNotThrow(() -> Class.forName("ch.unibe.cs.mergeci.CiCdMergeResolverApplication"),
                "CiCdMergeResolverApplication class should exist");
    }

    @Test
    void testApplicationHasSpringBootAnnotation() {
        Class<?> appClass = CiCdMergeResolverApplication.class;
        assertTrue(appClass.isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class),
                "Application should have @SpringBootApplication annotation");
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
