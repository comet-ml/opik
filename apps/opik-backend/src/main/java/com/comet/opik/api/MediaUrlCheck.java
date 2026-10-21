package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MediaUrlCheck(
        @NotNull @Size(min = 1, max = MediaUrlCheck.MAX_URLS) @Schema(description = "Media URLs to check, at most 20") List<@NotBlank String> urls) {

    public static final int MAX_URLS = 20;

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        OK("ok"),
        SKIPPED("skipped"),
        INVALID_URL("invalid_url"),
        BLOCKED("blocked"),
        UNREACHABLE("unreachable"),
        TIMEOUT("timeout"),
        TOO_MANY_REDIRECTS("too_many_redirects"),
        HTTP_ERROR("http_error"),
        NOT_MEDIA("not_media"),
        ;

        @JsonValue
        private final String value;

        @JsonCreator
        public static Status fromString(String value) {
            return Arrays.stream(values())
                    .filter(status -> status.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown media URL check status '%s'".formatted(value)));
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(
            @NonNull String url,
            @NonNull @Schema(description = "ok when the URL answered with an image, video or audio file; skipped for data URIs") Status status,
            @Schema(description = "HTTP status of the last response, when there was one") Integer httpStatus,
            @Schema(description = "Content type of the last response, without parameters") String contentType) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Results(
            @NonNull @Schema(description = "One result per requested URL, in request order") List<Result> results) {
    }
}
