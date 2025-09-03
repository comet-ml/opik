package com.comet.opik.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.stringtemplate.v4.ST;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for StringTemplateManager to ensure proper template caching and reuse.
 */
class StringTemplateManagerTest {

    private StringTemplateManager templateManager;

    @BeforeEach
    void setUp() {
        templateManager = StringTemplateManager.getInstance();
        // Clear cache before each test to ensure clean state
        templateManager.clearCache();
    }

    @Test
    void getTemplate() {
        // Given
        String templateString = "Hello <name>!";

        // When
        ST template1 = templateManager.getTemplate(templateString);
        ST template2 = templateManager.getTemplate(templateString);

        // Then
        assertThat(template1).isNotNull();
        assertThat(template2).isNotNull();
        // Should return the same instance due to caching
        assertThat(template1).isSameAs(template2);
        assertThat(templateManager.getCacheSize()).isEqualTo(1);
    }

    @Test
    void getTemplateWithName() {
        // Given
        String templateName = "greeting";
        String templateString = "Hello <name>!";

        // When
        ST template1 = templateManager.getTemplate(templateName, templateString);
        ST template2 = templateManager.getTemplate(templateName, templateString);

        // Then
        assertThat(template1).isNotNull();
        assertThat(template2).isNotNull();
        // Should return the same instance due to caching
        assertThat(template1).isSameAs(template2);
        assertThat(templateManager.getCacheSize()).isEqualTo(1);
    }

    @Test
    void getTemplateWithDifferentNames() {
        // Given
        String templateName1 = "greeting";
        String templateName2 = "farewell";
        String templateString = "Hello <name>!";

        // When
        ST template1 = templateManager.getTemplate(templateName1, templateString);
        ST template2 = templateManager.getTemplate(templateName2, templateString);

        // Then
        assertThat(template1).isNotNull();
        assertThat(template2).isNotNull();
        // Should return different instances due to different cache keys
        assertThat(template1).isNotSameAs(template2);
        assertThat(templateManager.getCacheSize()).isEqualTo(2);
    }

    @Test
    void getTemplateWithDifferentStrings() {
        // Given
        String templateString1 = "Hello <name>!";
        String templateString2 = "Goodbye <name>!";

        // When
        ST template1 = templateManager.getTemplate(templateString1);
        ST template2 = templateManager.getTemplate(templateString2);

        // Then
        assertThat(template1).isNotNull();
        assertThat(template2).isNotNull();
        // Should return different instances due to different template strings
        assertThat(template1).isNotSameAs(template2);
        assertThat(templateManager.getCacheSize()).isEqualTo(2);
    }

    @Test
    void createAndCacheTemplate() {
        // Given
        String templateString = "Hello <name>!";

        // When
        ST template = templateManager.createAndCacheTemplate(templateString);

        // Then
        assertThat(template).isNotNull();
        assertThat(templateManager.isTemplateCached(templateString)).isTrue();
        assertThat(templateManager.getCacheSize()).isEqualTo(1);
    }

    @Test
    void removeTemplate() {
        // Given
        String templateString = "Hello <name>!";
        templateManager.getTemplate(templateString);
        assertThat(templateManager.getCacheSize()).isEqualTo(1);

        // When
        boolean removed = templateManager.removeTemplate(templateString);

        // Then
        assertThat(removed).isTrue();
        assertThat(templateManager.getCacheSize()).isEqualTo(0);
        assertThat(templateManager.isTemplateCached(templateString)).isFalse();
    }

    @Test
    void removeTemplateNotInCache() {
        // Given
        String templateString = "Hello <name>!";

        // When
        boolean removed = templateManager.removeTemplate(templateString);

        // Then
        assertThat(removed).isFalse();
        assertThat(templateManager.getCacheSize()).isEqualTo(0);
    }

    @Test
    void clearCache() {
        // Given
        templateManager.getTemplate("Hello <name>!");
        templateManager.getTemplate("Goodbye <name>!");
        assertThat(templateManager.getCacheSize()).isEqualTo(2);

        // When
        templateManager.clearCache();

        // Then
        assertThat(templateManager.getCacheSize()).isEqualTo(0);
    }

    @Test
    void templateReuseAfterClear() {
        // Given
        String templateString = "Hello <name>!";
        ST template1 = templateManager.getTemplate(templateString);
        templateManager.clearCache();

        // When
        ST template2 = templateManager.getTemplate(templateString);

        // Then
        assertThat(template1).isNotNull();
        assertThat(template2).isNotNull();
        // Should return different instances after cache clear
        assertThat(template1).isNotSameAs(template2);
        assertThat(templateManager.getCacheSize()).isEqualTo(1);
    }

    @Test
    void templateFunctionality() {
        // Given
        String templateString = "Hello <name>!";
        ST template = templateManager.getTemplate(templateString);

        // When
        template.add("name", "World");
        String result = template.render();

        // Then
        assertThat(result).isEqualTo("Hello World!");
    }

    @Test
    void multipleTemplateInstances() {
        // Given
        String templateString = "Hello <name>!";

        // When - get the same template multiple times
        ST template1 = templateManager.getTemplate(templateString);
        ST template2 = templateManager.getTemplate(templateString);
        ST template3 = templateManager.getTemplate(templateString);

        // Then - should return the same instance due to caching
        assertThat(template1).isSameAs(template2);
        assertThat(template2).isSameAs(template3);
        assertThat(templateManager.getCacheSize()).isEqualTo(1);

        // Verify that the template can be used multiple times with different attributes
        // First use
        template1.add("name", "Alice");
        String result1 = template1.render();
        assertThat(result1).isEqualTo("Hello Alice!");

        // Reset template for second use (clear previous attributes)
        template1.remove("name");
        template1.add("name", "Bob");
        String result2 = template1.render();
        assertThat(result2).isEqualTo("Hello Bob!");

        // Third use with different name
        template1.remove("name");
        template1.add("name", "Charlie");
        String result3 = template1.render();
        assertThat(result3).isEqualTo("Hello Charlie!");
    }

    @Test
    void cacheSizeAccuracy() {
        // Given
        assertThat(templateManager.getCacheSize()).isEqualTo(0);

        // When
        templateManager.getTemplate("Template 1");
        templateManager.getTemplate("Template 2");
        templateManager.getTemplate("Template 3");

        // Then
        assertThat(templateManager.getCacheSize()).isEqualTo(3);
    }

    @Test
    void isTemplateCached() {
        // Given
        String templateString = "Hello <name>!";
        assertThat(templateManager.isTemplateCached(templateString)).isFalse();

        // When
        templateManager.getTemplate(templateString);

        // Then
        assertThat(templateManager.isTemplateCached(templateString)).isTrue();
    }
}
