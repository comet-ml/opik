package com.comet.opik.api;

import com.comet.opik.api.validation.FeedbackValidation;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.domain.FeedbackDefinitionModel.FeedbackType;

@Data
@SuperBuilder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = FeedbackDefinition.NumericalFeedbackDefinition.class, name = "numerical"),
        @JsonSubTypes.Type(value = FeedbackDefinition.CategoricalFeedbackDefinition.class, name = "categorical"),
        @JsonSubTypes.Type(value = FeedbackDefinition.BooleanFeedbackDefinition.class, name = "boolean")
})
@Schema(name = "Feedback", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = "numerical", schema = FeedbackDefinition.NumericalFeedbackDefinition.class),
        @DiscriminatorMapping(value = "categorical", schema = FeedbackDefinition.CategoricalFeedbackDefinition.class),
        @DiscriminatorMapping(value = "boolean", schema = FeedbackDefinition.BooleanFeedbackDefinition.class)
})
@RequiredArgsConstructor
@FeedbackValidation
public abstract sealed class FeedbackDefinition<T> {

    @Getter
    @SuperBuilder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class NumericalFeedbackDefinition
            extends
                FeedbackDefinition<NumericalFeedbackDefinition.NumericalFeedbackDetail> {

        @Data
        @Builder(toBuilder = true)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class NumericalFeedbackDetail {

            @JsonView({View.Public.class, View.Create.class, View.Update.class})
            @NotNull private final BigDecimal max;

            @JsonView({View.Public.class, View.Create.class, View.Update.class})
            @NotNull private final BigDecimal min;

            @ConstructorProperties({"max", "min"})
            public NumericalFeedbackDetail(@NotNull BigDecimal max, @NotNull BigDecimal min) {
                this.max = max;
                this.min = min;
            }
        }

        @NotNull @JsonView({View.Public.class, View.Create.class, View.Update.class})
        private final NumericalFeedbackDetail details;

        @ConstructorProperties({"id", "name", "description", "details", "createdAt", "createdBy", "lastUpdatedAt",
                "lastUpdatedBy"})
        public NumericalFeedbackDefinition(UUID id, @NotBlank String name, String description,
                @NotNull NumericalFeedbackDetail details, Instant createdAt, String createdBy,
                Instant lastUpdatedAt, String lastUpdatedBy) {
            super(id, name, description, createdAt, createdBy, lastUpdatedAt, lastUpdatedBy);
            this.details = details;
        }

        @Override
        public FeedbackType getType() {
            return FeedbackType.NUMERICAL;
        }
    }

    @Getter
    @SuperBuilder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class CategoricalFeedbackDefinition
            extends
                FeedbackDefinition<CategoricalFeedbackDefinition.CategoricalFeedbackDetail> {

        @Data
        @Builder(toBuilder = true)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class CategoricalFeedbackDetail {

            @NotNull @Size(min = 2) @JsonView({View.Public.class, View.Create.class, View.Update.class})
            private final Map<String, Double> categories;

            @ConstructorProperties({"categories"})
            public CategoricalFeedbackDetail(@NotNull @Size(min = 2) Map<String, Double> categories) {
                this.categories = categories;
            }
        }

        @NotNull @JsonView({View.Public.class, View.Create.class, View.Update.class})
        private final CategoricalFeedbackDetail details;

        @ConstructorProperties({"id", "name", "description", "details", "createdAt", "createdBy", "lastUpdatedAt",
                "lastUpdatedBy"})
        public CategoricalFeedbackDefinition(UUID id, @NotBlank String name, String description,
                @NotNull CategoricalFeedbackDetail details, Instant createdAt, String createdBy,
                Instant lastUpdatedAt, String lastUpdatedBy) {
            super(id, name, description, createdAt, createdBy, lastUpdatedAt, lastUpdatedBy);
            this.details = details;
        }

        @Override
        public FeedbackType getType() {
            return FeedbackType.CATEGORICAL;
        }
    }

    @Getter
    @SuperBuilder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class BooleanFeedbackDefinition
            extends
                FeedbackDefinition<BooleanFeedbackDefinition.BooleanFeedbackDetail> {

        @Data
        @Builder(toBuilder = true)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class BooleanFeedbackDetail {

            @NotBlank @JsonView({View.Public.class, View.Create.class, View.Update.class})
            @Schema(description = "Label for true/1 value", example = "Pass")
            private final String trueLabel;

            @NotBlank @JsonView({View.Public.class, View.Create.class, View.Update.class})
            @Schema(description = "Label for false/0 value", example = "Fail")
            private final String falseLabel;

            @ConstructorProperties({"trueLabel", "falseLabel"})
            public BooleanFeedbackDetail(@NotBlank String trueLabel, @NotBlank String falseLabel) {
                this.trueLabel = trueLabel;
                this.falseLabel = falseLabel;
            }
        }

        @NotNull @JsonView({View.Public.class, View.Create.class, View.Update.class})
        private final BooleanFeedbackDetail details;

        @ConstructorProperties({"id", "name", "description", "details", "createdAt", "createdBy", "lastUpdatedAt",
                "lastUpdatedBy"})
        public BooleanFeedbackDefinition(UUID id, @NotBlank String name, String description,
                @NotNull BooleanFeedbackDetail details, Instant createdAt, String createdBy,
                Instant lastUpdatedAt, String lastUpdatedBy) {
            super(id, name, description, createdAt, createdBy, lastUpdatedAt, lastUpdatedBy);
            this.details = details;
        }

        @Override
        public FeedbackType getType() {
            return FeedbackType.BOOLEAN;
        }
    }

    public static class View {
        public static class Create {
        }

        public static class Public {
        }

        public static class Update {
        }
    }

    public record FeedbackDefinitionPage(
            @JsonView( {
                    View.Public.class}) int page,
            @JsonView({View.Public.class}) int size,
            @JsonView({View.Public.class}) long total,
            @JsonView({View.Public.class}) List<FeedbackDefinition<?>> content) implements Page<FeedbackDefinition<?>>{
    }

    // Fields and methods

    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final UUID id;

    @NotBlank @JsonView({View.Public.class, View.Create.class, View.Update.class})
    private final String name;

    @Size(max = 255, message = "cannot exceed 255 characters") @JsonView({View.Public.class, View.Create.class, View.Update.class})
    @Schema(description = "Optional description for the feedback definition", example = "This feedback definition is used to rate response quality")
    private final String description;

    /**
     * JSON is ignored as details type is polymorphic per subclass, so it's excluded from the Swagger definition
     * of the base object. Otherwise, some SDKs have troubles to deal with conflicting types between the base and the
     * subclass.
     * <p>
     * Subclasses should always override JSON ignore and serialize their details into JSON, so details are present
     * in both the Swagger definition and the actual JSON payload.
     *
     * @return the particular type of details.
     */
    @JsonIgnore
    public abstract T getDetails();

    @Nullable @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final Instant createdAt;

    @Nullable @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final String createdBy;

    @Nullable @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final Instant lastUpdatedAt;

    @Nullable @JsonView({View.Public.class})
    @Schema(accessMode = Schema.AccessMode.READ_ONLY)
    private final String lastUpdatedBy;

    @NotNull @JsonView({View.Public.class, View.Create.class, View.Update.class})
    public abstract FeedbackType getType();
}