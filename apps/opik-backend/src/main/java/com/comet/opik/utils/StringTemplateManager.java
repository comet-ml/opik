package com.comet.opik.utils;

import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages StringTemplate instances to prevent memory leaks caused by repeated
 * creation of new ST objects. This class implements template pooling and reuse
 * to improve performance and reduce memory allocation.
 *
 * <p>The manager maintains a thread-safe cache of compiled templates and provides
 * methods to retrieve and use templates without creating new instances each time.</p>
 */
@Slf4j
public class StringTemplateManager {

    private static final StringTemplateManager INSTANCE = new StringTemplateManager();

    // Cache for compiled templates to avoid repeated compilation
    private final Map<String, ST> templateCache = new ConcurrentHashMap<>();

    private StringTemplateManager() {
        // Private constructor for singleton pattern
    }

    /**
     * Returns the singleton instance of the StringTemplateManager.
     *
     * @return the singleton instance
     */
    public static StringTemplateManager getInstance() {
        return INSTANCE;
    }

    /**
     * Gets a template instance for the given template string. If the template
     * doesn't exist in the cache, it creates and caches a new one.
     *
     * @param templateString the template string to compile and cache
     * @return a reusable ST instance
     */
    public ST getTemplate(String templateString) {
        return templateCache.computeIfAbsent(templateString, this::createTemplate);
    }

    /**
     * Gets a template instance for the given template string with a specific name.
     * This is useful for templates that need to be identified by name.
     *
     * @param templateName the name of the template
     * @param templateString the template string to compile and cache
     * @return a reusable ST instance
     */
    public ST getTemplate(String templateName, String templateString) {
        String cacheKey = templateName + ":" + templateString.hashCode();
        return templateCache.computeIfAbsent(cacheKey, k -> createTemplate(templateString));
    }

    /**
     * Creates a new ST instance from the template string. This method is called
     * when a template is not found in the cache.
     *
     * @param templateString the template string to compile
     * @return a new ST instance
     */
    private ST createTemplate(String templateString) {
        try {
            ST template = new ST(templateString);
            log.debug("Created new StringTemplate instance for template hash: '{}'", templateString.hashCode());
            return template;
        } catch (Exception exception) {
            log.error("Failed to create StringTemplate instance", exception);
            throw new RuntimeException("Failed to create StringTemplate", exception);
        }
    }

    /**
     * Clears the template cache. This can be useful for memory management
     * or when templates need to be refreshed.
     */
    public void clearCache() {
        int cacheSize = templateCache.size();
        templateCache.clear();
        log.info("Cleared StringTemplate cache, removed '{}' templates", cacheSize);
    }

    /**
     * Gets the current size of the template cache.
     *
     * @return the number of cached templates
     */
    public int getCacheSize() {
        return templateCache.size();
    }

    /**
     * Removes a specific template from the cache.
     *
     * @param templateString the template string to remove
     * @return true if the template was removed, false if it wasn't in the cache
     */
    public boolean removeTemplate(String templateString) {
        ST removed = templateCache.remove(templateString);
        if (removed != null) {
            log.debug("Removed template from cache for hash: '{}'", templateString.hashCode());
            return true;
        }
        return false;
    }

    /**
     * Creates a new ST instance with the given template string and immediately
     * adds it to the cache. This method is useful for templates that are
     * expected to be reused.
     *
     * @param templateString the template string
     * @return a new ST instance that is now cached
     */
    public ST createAndCacheTemplate(String templateString) {
        ST template = createTemplate(templateString);
        templateCache.put(templateString, template);
        return template;
    }

    /**
     * Checks if a template is currently cached.
     *
     * @param templateString the template string to check
     * @return true if the template is cached, false otherwise
     */
    public boolean isTemplateCached(String templateString) {
        return templateCache.containsKey(templateString);
    }
}
