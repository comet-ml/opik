package com.comet.opik.domain.llm.langchain4j;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.InputAudio;
import dev.langchain4j.model.openai.internal.chat.PdfFile;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@JsonDeserialize(builder = OpikContent.Builder.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@EqualsAndHashCode
@ToString
public final class OpikContent {

    @JsonProperty
    private final OpikContentType type;
    @JsonProperty
    private final String text;
    @JsonProperty
    private final ImageUrl imageUrl;
    @JsonProperty
    private final VideoUrl videoUrl;
    @JsonProperty
    private final AudioUrl audioUrl;
    @JsonProperty
    private final InputAudio inputAudio;
    @JsonProperty
    private final PdfFile file;

    public OpikContent(Builder builder) {
        this.type = builder.type;
        this.text = builder.text;
        this.imageUrl = builder.imageUrl;
        this.videoUrl = builder.videoUrl;
        this.audioUrl = builder.audioUrl;
        this.inputAudio = builder.inputAudio;
        this.file = builder.file;
    }

    public OpikContentType type() {
        return type;
    }

    public String text() {
        return text;
    }

    public ImageUrl imageUrl() {
        return imageUrl;
    }

    public VideoUrl videoUrl() {
        return videoUrl;
    }

    public AudioUrl audioUrl() {
        return audioUrl;
    }

    public InputAudio inputAudio() {
        return inputAudio;
    }

    public PdfFile file() {
        return file;
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class Builder {

        private OpikContentType type;
        private String text;
        private ImageUrl imageUrl;
        private VideoUrl videoUrl;
        private AudioUrl audioUrl;
        private InputAudio inputAudio;
        private PdfFile file;

        public Builder type(OpikContentType type) {
            this.type = type;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder imageUrl(ImageUrl imageUrl) {
            this.imageUrl = imageUrl;
            return this;
        }

        public Builder videoUrl(VideoUrl videoUrl) {
            this.videoUrl = videoUrl;
            return this;
        }

        public Builder audioUrl(AudioUrl audioUrl) {
            this.audioUrl = audioUrl;
            return this;
        }

        public Builder inputAudio(InputAudio inputAudio) {
            this.inputAudio = inputAudio;
            return this;
        }

        public Builder file(PdfFile file) {
            this.file = file;
            return this;
        }

        public OpikContent build() {
            return new OpikContent(this);
        }
    }
}
