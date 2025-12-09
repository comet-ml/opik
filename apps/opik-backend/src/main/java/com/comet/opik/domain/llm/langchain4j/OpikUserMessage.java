package com.comet.opik.domain.llm.langchain4j;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import dev.langchain4j.model.openai.internal.chat.ImageDetail;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.InputAudio;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.PdfFile;
import dev.langchain4j.model.openai.internal.chat.Role;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

import static dev.langchain4j.model.openai.internal.chat.Role.USER;
import static java.util.Collections.unmodifiableList;

/**
 * This class is basically a copy-paste from langchain4j's OpenAI UserMessage, adding
 * support for video URLs. This is a hack that we can remove once they add support for it.
 */
@JsonDeserialize(builder = OpikUserMessage.Builder.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@EqualsAndHashCode
@ToString
public final class OpikUserMessage implements Message {

    @JsonProperty
    private final Role role = USER;
    @JsonProperty
    private final Object content;
    @JsonProperty
    private final String name;

    public OpikUserMessage(Builder builder) {
        this.content = builder.stringContent != null ? builder.stringContent : builder.content;
        this.name = builder.name;
    }

    public Role role() {
        return role;
    }

    public Object content() {
        return content;
    }

    public String name() {
        return name;
    }

    public static OpikUserMessage from(String text) {
        return OpikUserMessage.builder()
                .content(text)
                .build();
    }

    public static OpikUserMessage from(String text, String... imageUrls) {
        return OpikUserMessage.builder()
                .addText(text)
                .addImageUrls(imageUrls)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static final class Builder {

        private String stringContent; // keeping it for compatibility with other OpenAI-like APIs
        private List<OpikContent> content;
        private String name;

        public Builder addText(String text) {
            initializeContent();
            OpikContent content = OpikContent.builder()
                    .type(OpikContentType.TEXT)
                    .text(text)
                    .build();
            this.content.add(content);
            return this;
        }

        public Builder addImageUrl(String imageUrl) {
            return addImageUrl(imageUrl, null);
        }

        public Builder addImageUrl(String imageUrl, ImageDetail imageDetail) {
            initializeContent();
            OpikContent content = OpikContent.builder()
                    .type(OpikContentType.IMAGE_URL)
                    .imageUrl(ImageUrl.builder()
                            .url(imageUrl)
                            .detail(imageDetail)
                            .build())
                    .build();
            this.content.add(content);
            return this;
        }

        public Builder addImageUrls(String... imageUrls) {
            for (String imageUrl : imageUrls) {
                addImageUrl(imageUrl);
            }
            return this;
        }

        public Builder addVideoUrl(String videoUrl) {
            initializeContent();
            OpikContent content = OpikContent.builder()
                    .type(OpikContentType.VIDEO_URL)
                    .videoUrl(VideoUrl.builder()
                            .url(videoUrl)
                            .build())
                    .build();
            this.content.add(content);
            return this;
        }

        public Builder addAudioUrl(String audioUrl) {
            initializeContent();
            OpikContent content = OpikContent.builder()
                    .type(OpikContentType.AUDIO_URL)
                    .audioUrl(AudioUrl.builder()
                            .url(audioUrl)
                            .build())
                    .build();
            this.content.add(content);
            return this;
        }

        public Builder addInputAudio(InputAudio inputAudio) {
            initializeContent();
            this.content.add(
                    OpikContent.builder()
                            .type(OpikContentType.AUDIO)
                            .inputAudio(inputAudio)
                            .build());

            return this;
        }

        public Builder addPdfFile(PdfFile pdfFile) {
            initializeContent();
            this.content.add(
                    OpikContent.builder()
                            .type(OpikContentType.FILE)
                            .file(pdfFile)
                            .build());

            return this;
        }

        public Builder content(List<OpikContent> content) {
            if (content != null) {
                this.content = unmodifiableList(content);
            }
            return this;
        }

        public Builder content(String content) {
            this.stringContent = content;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public OpikUserMessage build() {
            return new OpikUserMessage(this);
        }

        private void initializeContent() {
            if (this.content == null) {
                this.content = new ArrayList<>();
            }
        }
    }
}
