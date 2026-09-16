package com.comet.opik.domain.export;

import com.comet.opik.api.ExportParams;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

/**
 * Resolves an export type to the source that handles it, and teaches Jackson about each source's params record so
 * the opaque {@code params} column round-trips without a hardcoded subtype list.
 */
@Slf4j
@Singleton
public class ExportSourceRegistry {

    private final Map<String, ExportSource> sourcesByType;

    @Inject
    public ExportSourceRegistry(@NonNull Set<ExportSource> sources) {
        this.sourcesByType = sources.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ExportSource::exportType, source -> source));

        // Registering here rather than in a static @JsonSubTypes list is what lets a new export type ship without
        // editing ExportParams.
        sources.forEach(source -> JsonUtils.getMapper()
                .registerSubtypes(new NamedType(source.paramsType(), source.exportType())));

        log.info("Registered '{}' export source(s): {}", sourcesByType.size(), sourcesByType.keySet());
    }

    public ExportSource get(@NonNull String exportType) {
        ExportSource source = sourcesByType.get(exportType);

        if (source == null) {
            throw new BadRequestException("Unsupported export type: '%s'".formatted(exportType));
        }

        return source;
    }

    public ExportSource get(@NonNull ExportParams params) {
        return get(params.exportType());
    }

    public Set<String> supportedTypes() {
        return sourcesByType.keySet();
    }
}
