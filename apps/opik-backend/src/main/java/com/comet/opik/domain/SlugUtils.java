package com.comet.opik.domain;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Utility class for generating URL-safe slugs from dashboard names.
 */
@UtilityClass
public class SlugUtils {

    private static final int MAX_SLUG_LENGTH = 150;

    /**
     * Generates a URL-safe slug from a given name.
     *
     * @param name the name to convert to a slug
     * @return the generated slug
     */
    public static String generateSlug(String name) {
        if (StringUtils.isBlank(name)) {
            return "";
        }

        // Normalize Unicode characters (NFD = canonical decomposition)
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD);

        // Remove diacritics/accents
        String withoutDiacritics = normalized.replaceAll("\\p{M}", "");

        // Convert to lowercase
        String lowercase = withoutDiacritics.toLowerCase(Locale.ENGLISH);

        // Replace non-alphanumeric characters with hyphens
        String slug = lowercase.replaceAll("[^a-z0-9]+", "-");

        // Remove leading/trailing hyphens
        slug = slug.replaceAll("^-+|-+$", "");

        // Limit length to match database constraint
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH);
            // Remove trailing hyphen if present after truncation
            slug = slug.replaceAll("-+$", "");
        }

        return slug;
    }

    /**
     * Generates a unique slug by appending a number suffix if needed.
     *
     * @param baseSlug the base slug to make unique
     * @param maxSuffix the highest suffix in use for the base slug (the bare base slug counts as 1), or 0 if none
     * @return the unique slug
     */
    public static String generateUniqueSlug(String baseSlug, long maxSuffix) {
        if (StringUtils.isBlank(baseSlug)) {
            return baseSlug;
        }
        if (maxSuffix == 0) {
            return baseSlug;
        }

        String suffix = "-" + (maxSuffix + 1);
        String slug = baseSlug + suffix;

        // Ensure the slug doesn't exceed database constraint
        if (slug.length() > MAX_SLUG_LENGTH) {
            int maxBaseLength = MAX_SLUG_LENGTH - suffix.length();
            slug = baseSlug.substring(0, maxBaseLength) + suffix;
        }

        return slug;
    }
}
