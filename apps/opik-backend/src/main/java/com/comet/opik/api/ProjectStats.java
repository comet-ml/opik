package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonView;
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

        @JsonView({View.Public.class})
        private final String name;

        /**
         * JSON is ignored as value type is polymorphic per subclass, so it's excluded from the Swagger definition
         * of the base object. Otherwise, both the Python and Javascript SDKs have troubles to deal with conflicting
         * types between the base and the subclass.
         * <p>
         * Subclasses should always override JSON ignore and serialize their value into JSON, so value is present in
         * both the Swagger definition and the actual JSON payload.
         *
         * @return the particular value.
         */
        @JsonIgnore
        public abstract T getValue();

        @JsonView({View.Public.class})
        private final StatsType type;

        public static class View {
            public static class Public {
            }
        }
    }

    @ToString(callSuper = true)
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @Getter
    public abstract static sealed class SingleValueStat<T extends Number> extends ProjectStatItem<T> {

        @JsonView({View.Public.class})
        private final T value;
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
    public static final class AvgValueStat extends SingleValueStat<Double> {

        @ConstructorProperties({"name", "value"})
        public AvgValueStat(String name, Double value) {
            super(AvgValueStat.builder().value(value).name(name).type(StatsType.AVG));
        }
    }

    @ToString(callSuper = true)
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @Getter
    public static final class PercentageValueStat extends ProjectStatItem<PercentageValues> {

        @JsonView({View.Public.class})
        private final PercentageValues value;

        @ConstructorProperties({"name", "value"})
        public PercentageValueStat(String name, PercentageValues value) {
            super(name, StatsType.PERCENTAGE);
            this.value = value;
        }
    }

    public enum StatsType {
        COUNT,
        PERCENTAGE,
        AVG
    }
}
