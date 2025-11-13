package com.comet.opik.domain;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Utility class for generating URL-safe slugs from dashboard names.
 */
@UtilityClass
public class SlugUtils {

    /**
     * Generates a URL-safe slug from a given name.
     *
     * @param name the name to convert to a slug
     * @return the generated slug
     */
    public static String generateSlug(@NonNull String name) {
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

        // Limit length to 140 characters (matching database constraint)
        if (slug.length() > 140) {
            slug = slug.substring(0, 140);
            // Remove trailing hyphen if present after truncation
            slug = slug.replaceAll("-+$", "");
        }

        return slug;
    }

    /**
     * Generates a unique slug by appending a number suffix if needed.
     *
     * @param baseSlug the base slug to make unique
     * @param existingCount the count of existing slugs with the same prefix
     * @return the unique slug
     */
    public static String generateUniqueSlug(@NonNull String baseSlug, long existingCount) {
        if (existingCount == 0) {
            return baseSlug;
        }

        String suffix = "-" + (existingCount + 1);
        String slug = baseSlug + suffix;

        // Ensure the slug doesn't exceed 140 characters
        if (slug.length() > 140) {
            int maxBaseLength = 140 - suffix.length();
            slug = baseSlug.substring(0, maxBaseLength) + suffix;
        }

        return slug;
    }
}
