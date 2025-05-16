package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

@Mapper(imports = Instant.class)
public interface SpanMapper {

    SpanMapper INSTANCE = Mappers.getMapper(SpanMapper.class);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "duration", ignore = true)
    void updateSpanBuilder(@MappingTarget Span.SpanBuilder spanBuilder, SpanUpdate spanUpdate);
}
