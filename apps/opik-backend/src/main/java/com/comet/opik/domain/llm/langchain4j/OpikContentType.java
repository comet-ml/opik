package com.comet.opik.domain.llm.langchain4j;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum OpikContentType {

    @JsonProperty("text")
    TEXT,
    @JsonProperty("image_url")
    IMAGE_URL,
    @JsonProperty("video_url")
    VIDEO_URL,
    @JsonProperty("audio_url")
    AUDIO_URL,
    @JsonProperty("input_audio")
    AUDIO,
    @JsonProperty("file")
    FILE
}
