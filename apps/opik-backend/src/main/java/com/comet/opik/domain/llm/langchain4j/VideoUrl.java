package com.comet.opik.domain.llm.langchain4j;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@JsonDeserialize(builder = VideoUrl.Builder.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@EqualsAndHashCode
@ToString
public final class VideoUrl {

    @JsonProperty
    private final String url;

    @JsonProperty
    private final String mimeType;

    public VideoUrl(Builder builder) {
        this.url = builder.url;
        this.mimeType = builder.mimeType;
    }

    public String url() {
        return url;
    }

    public String mimeType() {
        return mimeType;
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class Builder {

        private String url;
        private String mimeType;

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public VideoUrl build() {
            return new VideoUrl(this);
        }
    }
}
