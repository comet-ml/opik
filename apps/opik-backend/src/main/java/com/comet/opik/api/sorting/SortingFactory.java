package com.comet.opik.api.sorting;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.util.List;

public class SortingFactory {
    public List<SortingField> newSorting(String queryParam) {
        if (StringUtils.isBlank(queryParam)) {
            return null;
        }

        List<SortingField> sorting;
        try {
            sorting = JsonUtils.readValue(queryParam, new TypeReference<>() {
            });
        } catch (UncheckedIOException exception) {
            throw new BadRequestException("Invalid sorting query parameter '%s'".formatted(queryParam), exception);
        }

        return sorting.isEmpty() ? null : sorting;
    }
}
