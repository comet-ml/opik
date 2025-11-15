package com.comet.opik.domain.llm.langchain4j;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.Objects;

@JsonDeserialize(builder = VideoUrl.Builder.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class VideoUrl {

    @JsonProperty
    private final String url;

    public VideoUrl(Builder builder) {
        this.url = builder.url;
    }

    public String url() {
        return url;
    }

    public String getUrl() {
        return url;
    }

    @Override
    public boolean equals(Object another) {
        if (this == another) return true;
        return another instanceof VideoUrl
                && equalTo((VideoUrl) another);
    }

    private boolean equalTo(VideoUrl another) {
        return Objects.equals(url, another.url);
    }

    @Override
    public int hashCode() {
        int h = 5381;
        h += (h << 5) + Objects.hashCode(url);
        return h;
    }

    @Override
    public String toString() {
        return "VideoUrl{" +
                "url=" + url +
                "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class Builder {

        private String url;

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public VideoUrl build() {
            return new VideoUrl(this);
        }
    }
}
