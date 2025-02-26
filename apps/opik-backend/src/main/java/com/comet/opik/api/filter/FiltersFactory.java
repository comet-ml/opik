package com.comet.opik.api.filter;

import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class FiltersFactory {

    private static final Map<FieldType, BiFunction<String, String, Boolean>> FIELD_TYPE_VALIDATION_MAP = new EnumMap<>(
            Map.of(
                    FieldType.STRING, (value, key) -> StringUtils.isNotBlank(value),
                    FieldType.DATE_TIME, (value, key) -> {
                        try {
                            Instant.parse(value);
                            return true;
                        } catch (DateTimeParseException exception) {
                            log.error("Invalid Instant format '{}'", value, exception);
                            return false;
                        }
                    },
                    FieldType.NUMBER, (value, key) -> NumberUtils.isParsable(value),
                    FieldType.FEEDBACK_SCORES_NUMBER, (value, key) -> {
                        if (StringUtils.isBlank(key)) {
                            return false;
                        }
                        try {
                            new BigDecimal(value);
                            return true;
                        } catch (NumberFormatException exception) {
                            log.error("Invalid BigDecimal format '{}'", value, exception);
                            return false;
                        }
                    },
                    FieldType.DICTIONARY, (value, key) -> StringUtils.isNotBlank(value) && StringUtils.isNotBlank(key),
                    FieldType.LIST, (value, key) -> StringUtils.isNotBlank(value),
                    FieldType.EMPTY, (value, key) -> true));

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
                .map(this::toValidAndDecoded)
                .toList();
        return filters.isEmpty() ? null : filters;
    }

    public <T extends Filter> List<T> validateFilter(List<T> filters) {
        if (CollectionUtils.isEmpty(filters)) {
            return filters;
        }
        return filters.stream()
                .map(this::toValidAndDecoded)
                .map(filter -> (T) filter)
                .toList();
    }

    private Filter toValidAndDecoded(Filter filter) {
        // Decode the value as first thing prior to any validation
        filter = filter.build(URLDecoder.decode(filter.value(), StandardCharsets.UTF_8));
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
        return FIELD_TYPE_VALIDATION_MAP.get(filter.field().getType()).apply(filter.value(), filter.key());
    }
}
