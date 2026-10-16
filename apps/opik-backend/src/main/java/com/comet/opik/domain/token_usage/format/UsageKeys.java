package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.OriginalUsage;
import jakarta.annotation.Nullable;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The provider's usage keys, looked up by normalized name. Names match ignoring case and separators
 * ({@code inputTokens}, {@code input_tokens} and {@code Input-Tokens} are the same name), after stripping the
 * namespaces a client prefixes every key with.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class UsageKeys {

    private static final Pattern CAMEL_CASE_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");

    // Only namespaces, never other prefixes: in names such as cache_read.input_tokens the qualifier comes first, and
    // stripping an unknown prefix would read the cache count as input.
    private static final List<String> NAMESPACES = List.of("gen_ai.usage.", "llm.token_count.");

    private static final List<String> TOTAL_NAMES = List.of("total_tokens", "total");

    private final Map<String, Long> countsByName;
    private final @Nullable Long reportedTotal;
    private final @Nullable Long outerPromptTokens;

    /** The provider's own keys. */
    static UsageKeys of(@NonNull OriginalUsage original) {
        var countsByName = countsByName(original.keys());
        Long reportedTotal = TOTAL_NAMES.stream().map(countsByName::get).filter(Objects::nonNull).findFirst()
                .orElseGet(() -> asLong(original.outerKeys().get("total_tokens")));
        return new UsageKeys(countsByName, reportedTotal, asLong(original.outerKeys().get("prompt_tokens")));
    }

    /** The provider's keys, with the outer keys the SDK sent next to them filling the counts they lack. */
    static UsageKeys withOuterKeys(@NonNull OriginalUsage original) {
        var providerKeys = of(original);
        return new UsageKeys(countsByName(original.withOuterKeys().keys()), providerKeys.reportedTotal,
                providerKeys.outerPromptTokens);
    }

    private static Map<String, Long> countsByName(Map<String, Integer> keys) {
        var countsByName = new HashMap<String, Long>();
        // OriginalUsage keeps its keys sorted, so when two spellings normalize to the same name the choice is stable.
        keys.forEach((key, value) -> countsByName.putIfAbsent(normalize(key), value.longValue()));
        return Map.copyOf(countsByName);
    }

    private static Long asLong(@Nullable Integer value) {
        return value == null ? null : value.longValue();
    }

    static String normalize(@NonNull String key) {
        String normalized = Arrays.stream(key.split("\\.", -1))
                .map(UsageKeys::normalizeSegment)
                .collect(Collectors.joining("."));
        return NAMESPACES.stream()
                .filter(namespace -> normalized.startsWith(namespace) && normalized.length() > namespace.length())
                .findFirst()
                .map(namespace -> normalized.substring(namespace.length()))
                .orElse(normalized);
    }

    private static String normalizeSegment(String segment) {
        return CAMEL_CASE_BOUNDARY.matcher(segment).replaceAll("$1_$2")
                .replace('-', '_')
                .toLowerCase(Locale.ROOT);
    }

    boolean has(String name) {
        return countsByName.containsKey(name);
    }

    boolean hasAny(String... names) {
        return Arrays.stream(names).anyMatch(this::has);
    }

    long get(String name) {
        return countsByName.getOrDefault(name, 0L);
    }

    @Nullable Long find(String name) {
        return countsByName.get(name);
    }

    /** The total the client reported, in the provider's usage or next to it when the SDK converted it. */
    @Nullable Long reportedTotal() {
        return reportedTotal;
    }

    /** The prompt count the SDK sent next to the provider's usage it converted, or null when it converted none. */
    @Nullable Long outerPromptTokens() {
        return outerPromptTokens;
    }

    /** The count under the first of the names that is present, or 0. */
    long first(String... names) {
        return Arrays.stream(names).filter(this::has).findFirst().map(this::get).orElse(0L);
    }

    /**
     * The count under the first of the names whose count isn't zero, or 0. For a count several shapes report under
     * their own names, where a shape that saw none still sends its zero.
     */
    long firstNonZero(String... names) {
        return Arrays.stream(names).map(this::find).filter(count -> count != null && count != 0).findFirst()
                .orElse(0L);
    }

    /** Whether the usage carries items of a list flattened as indexed keys ({@code iterations.0.input_tokens}). */
    boolean hasListItems(String listName) {
        return countsByName.keySet().stream().anyMatch(name -> listItemField(name, listName) != null);
    }

    /** The sum of one field over every item of a list flattened as indexed keys. */
    long sumOverListItems(String listName, String field) {
        return countsByName.entrySet().stream()
                .filter(entry -> field.equals(listItemField(entry.getKey(), listName)))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    private static String listItemField(String name, String listName) {
        String prefix = listName + ".";
        if (!name.startsWith(prefix)) {
            return null;
        }
        String rest = name.substring(prefix.length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || !rest.substring(0, dot).chars().allMatch(Character::isDigit)) {
            return null;
        }
        return rest.substring(dot + 1);
    }
}
