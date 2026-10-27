package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.exception.NonRetriableException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * An outbound provider request refused before it left Opik, so no attempt can succeed. The message is returned to
 * the caller, so it is always one of the fixed texts below and never carries input or detail.
 */
public class ProviderRequestRefusedException extends NonRetriableException {

    @RequiredArgsConstructor
    public enum Reason {
        DESTINATION(ProviderBaseUrlGuard.REFUSED),
        MODEL_IN_AUTHORITY("model can't be substituted into the provider host or port, only letters, digits, '.' and"
                + " '-' are allowed"),
        VERTEX_AI_LOCATION("Vertex AI location is not a valid region"),
        VERTEX_AI_TOKEN_URI("Vertex AI service account token_uri must be a Google token endpoint"),
        MEDIA_URL("a media URL was refused: it must be an http or https URL that resolves to a public address"
                + " (self-hosted deployments serving media from internal networks can set"
                + " LLM_PROVIDER_VIDEO_URL_DESTINATION_GUARD=relaxed)"),
        MEDIA_URL_DOWNLOAD("a media URL could not be downloaded: it must return the file itself, not a redirect, with a"
                + " success status, within the download time and size limits (LLM_PROVIDER_MEDIA_DOWNLOAD_TIMEOUT,"
                + " LLM_PROVIDER_MEDIA_DOWNLOAD_MAX_SIZE)"),
                ;

        private final String message;
    }

    public ProviderRequestRefusedException(@NonNull Reason reason) {
        super(reason.message);
    }
}
