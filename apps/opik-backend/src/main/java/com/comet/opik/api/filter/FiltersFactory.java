package com.comet.opik.api.filter;

import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class FiltersFactory {

    private static final String JSON_PREFIX = "$.";
    // Captures index for json key in form of "input[0]", group(1) is the key without index might be empty string, group(2) is the index if available
    private static final Pattern INDEX_PATTERN = Pattern.compile("^(.*?)(\\[\\d+])?$");

    private static final Map<FieldType, Function<Filter, Boolean>> FIELD_TYPE_VALIDATION_MAP = new EnumMap<>(
            ImmutableMap.<FieldType, Function<Filter, Boolean>>builder()
                    .put(FieldType.STRING, filter -> StringUtils.isNotBlank(filter.value()))
                    .put(FieldType.STRING_STATE_DB, filter -> StringUtils.isNotBlank(filter.value()))
                    .put(FieldType.ENUM, filter -> StringUtils.isNotBlank(filter.value()))
                    .put(FieldType.DATE_TIME, filter -> {
                        try {
                            Instant.parse(filter.value());
                            return true;
                        } catch (DateTimeParseException exception) {
                            log.error("Invalid Instant format '{}'", filter.value(), exception);
                            return false;
                        }
                    })
                    .put(FieldType.DATE_TIME_STATE_DB, filter -> {
                        try {
                            Instant.parse(filter.value());
                            return true;
                        } catch (DateTimeParseException exception) {
                            log.error("Invalid Instant format '{}'", filter.value(), exception);
                            return false;
                        }
                    })
                    .put(FieldType.NUMBER, filter -> NumberUtils.isParsable(filter.value()))
                    .put(FieldType.FEEDBACK_SCORES_NUMBER, filter -> {
                        if (StringUtils.isBlank(filter.key())) {
                            return false;
                        }
                        if (Operator.NO_VALUE_OPERATORS.contains(filter.operator())) {
                            // don't validate value in case it's not needed
                            return true;
                        }
                        try {
                            new BigDecimal(filter.value());
                            return true;
                        } catch (NumberFormatException exception) {
                            log.error("Invalid BigDecimal format '{}'", filter.value(), exception);
                            return false;
                        }
                    })
                    .put(FieldType.ERROR_CONTAINER, filter -> {
                        if (Operator.NO_VALUE_OPERATORS.contains(filter.operator())) {
                            // don't validate value in case it's not needed
                            return true;
                        }

                        return false;
                    })
                    .put(FieldType.DICTIONARY, filter -> filter.value() != null &&
                            StringUtils.isNotBlank(filter.key()))
                    .put(FieldType.DICTIONARY_STATE_DB, filter -> filter.value() != null &&
                            StringUtils.isNotBlank(filter.key()))
                    .put(FieldType.MAP, filter -> filter.value() != null &&
                            StringUtils.isNotBlank(filter.key()))
                    .put(FieldType.LIST, filter -> StringUtils.isNotBlank(filter.value()))
                    .build());

    private final @NonNull FilterQueryBuilder filterQueryBuilder;

    public <T extends Filter> List<? extends Filter> newFilters(String queryParam,
            @NonNull TypeReference<List<T>> valueTypeRef) {
        if (StringUtils.isBlank(queryParam)) {
            return null;
        }
        List<? extends Filter> filters;
        try {
            filters = JsonUtils.readValue(queryParam, valueTypeRef);
        } catch (UncheckedIOException exception) {
            throw new BadRequestException("Invalid filters query parameter '%s'".formatted(queryParam), exception);
        }

        filters = filters.stream()
                .distinct()
                .map(this::mapCustom)
                .map(this::toValidAndDecoded)
                .toList();
        return filters.isEmpty() ? null : filters;
    }

    public <T extends Filter> List<T> validateFilter(List<T> filters) {
        if (CollectionUtils.isEmpty(filters)) {
            return filters;
        }
        return filters.stream()
                .map(this::mapCustom)
                .map(this::toValidAndDecoded)
                .map(filter -> (T) filter)
                .toList();
    }

    private Filter toValidAndDecoded(Filter filter) {
        if (filter.field().getType() != FieldType.STRING) {
            // don't decode value for string fields as it is already decoded during JSON deserialization
            try {
                filter = filter.build(URLDecoder.decode(filter.value(), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException exception) {
                log.warn("failed to URL decode filter value '{}'", filter.value(), exception);
                throw new BadRequestException("Invalid filter '%s'".formatted(filter.value()), exception);
            }
        }

        if (filterQueryBuilder.toAnalyticsDbOperator(filter) == null) {
            throw new BadRequestException("Invalid operator '%s' for field '%s' of type '%s'"
                    .formatted(filter.operator().getQueryParamOperator(), filter.field().getQueryParamField(),
                            filter.field().getType()));
        }
        if (!validateFieldType(filter)) {
            throw new BadRequestException("Invalid value '%s' or key '%s' for field '%s' of type '%s'".formatted(
                    filter.value(), filter.key(), filter.field().getQueryParamField(), filter.field().getType()));
        }
        return filter;
    }

    private boolean validateFieldType(Filter filter) {
        return FIELD_TYPE_VALIDATION_MAP.get(filter.field().getType()).apply(filter);
    }

    public <T extends Filter> T mapCustom(T filter) {
        if (filter.field().getType() != FieldType.CUSTOM) {
            return filter;
        }

        filter = (T) filter.build(URLDecoder.decode(filter.value(), StandardCharsets.UTF_8));

        Pair<String, String> T2 = getFieldAndKey(filter.key());
        String customField = T2.getLeft();
        String customKey = T2.getRight();

        var mappedFilter = filter.buildFromCustom(customField,
                customKey == null ? FieldType.STRING : FieldType.DICTIONARY, filter.operator(), customKey,
                filter.value());

        if (mappedFilter == null) {
            throw new BadRequestException("Invalid key '%s' for custom filter".formatted(filter.key()));
        }

        return (T) mappedFilter;
    }

    private Pair<String, String> getFieldAndKey(String customKey) {
        if (StringUtils.isBlank(customKey)) {
            throw new BadRequestException("Custom key cannot be null or empty");
        }

        if (customKey.startsWith(JSON_PREFIX)) {
            customKey = customKey.substring(JSON_PREFIX.length());
        }

        int keyFieldSeparatorIndex = customKey.indexOf('.');

        if (keyFieldSeparatorIndex < 0) {
            // No check for matches() result as group(1) will always be present and group(2) will be null if no index is present
            Matcher matcher = INDEX_PATTERN.matcher(customKey);
            matcher.matches();

            return Pair.of(matcher.group(1), matcher.group(2));
        } else {
            String field = customKey.substring(0, keyFieldSeparatorIndex);
            String key = customKey.substring(keyFieldSeparatorIndex + 1);

            // No check for matches() result as group(1) will always be present and group(2) will be null if no index is present
            Matcher matcher = INDEX_PATTERN.matcher(field);
            matcher.matches();

            return Pair.of(matcher.group(1), Optional.ofNullable(matcher.group(2))
                    .map(index -> index + "." + key) // remove square brackets
                    .orElse(key));
        }

    }
}
