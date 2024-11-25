package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.math.BigDecimal;
import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectStats(List<ProjectStatItem<?>> stats) {

    public static ProjectStats empty() {
        return new ProjectStats(List.of());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = PercentageValueStat.class, name = "PERCENTAGE"),
            @JsonSubTypes.Type(value = CountValueStat.class, name = "COUNT"),
            @JsonSubTypes.Type(value = AvgValueStat.class, name = "AVG")
    })
    @Schema(discriminatorProperty = "type", discriminatorMapping = {
            @DiscriminatorMapping(value = "PERCENTAGE", schema = PercentageValueStat.class),
            @DiscriminatorMapping(value = "COUNT", schema = CountValueStat.class),
            @DiscriminatorMapping(value = "AVG", schema = AvgValueStat.class),
    })
    @RequiredArgsConstructor
    @Getter
    @SuperBuilder(toBuilder = true)
    @EqualsAndHashCode
    @ToString(callSuper = true)
    public abstract static sealed class ProjectStatItem<T> {
        private final String name;
        private final T value;
        private final StatsType type;
    }

    @ToString(callSuper = true)
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @Getter
    public abstract static sealed class SingleValueStat<T extends Number> extends ProjectStatItem<T> {
    }

    @ToString(callSuper = true)
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @Getter
    public static final class CountValueStat extends SingleValueStat<Long> {

        @ConstructorProperties({"name", "value"})
        public CountValueStat(String name, Long value) {
            super(CountValueStat.builder().value(value).name(name).type(StatsType.COUNT));
        }
    }

    @ToString(callSuper = true)
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @Getter
    public static final class AvgValueStat extends SingleValueStat<BigDecimal> {

        @ConstructorProperties({"name", "value"})
        public AvgValueStat(String name, BigDecimal value) {
            super(AvgValueStat.builder().value(value).name(name).type(StatsType.AVG));
        }
    }

    public record PercentageValues(double p50, double p90, double p99) {
    }

    @ToString(callSuper = true)
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @Getter
    public static final class PercentageValueStat extends ProjectStatItem<PercentageValues> {

        @ConstructorProperties({"name", "value"})
        public PercentageValueStat(String name, PercentageValues value) {
            super(PercentageValueStat.builder().value(value).name(name).type(StatsType.PERCENTAGE));
        }
    }

    public enum StatsType {
        COUNT,
        PERCENTAGE,
        AVG
    }
}
