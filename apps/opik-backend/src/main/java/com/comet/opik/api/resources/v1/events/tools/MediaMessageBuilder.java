package com.comet.opik.api.resources.v1.events.tools;

import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Builds the single multimodal {@link UserMessage} that {@link ToolCallLoop}
 * appends after a tool round in which {@code get_attachment} loaded one or more
 * attachments. Each {@link MediaPayload} becomes a langchain4j content part —
 * Base64-inlined (MinIO) via the two-arg {@code from(base64, mimeType)} factory,
 * or a presigned URL (S3) via the single-arg {@code from(url)} factory.
 *
 * <p>A leading {@link TextContent} labels the turn so the model understands the
 * media is the result of its tool calls rather than fresh user input.
 */
@UtilityClass
public final class MediaMessageBuilder {

    private static final String LEAD_IN = "The following attachment(s) you requested are provided below as"
            + " viewable media:";

    public static UserMessage build(@NonNull List<MediaPayload> media) {
        var builder = UserMessage.builder();
        builder.addContent(TextContent.from(LEAD_IN));

        for (var part : media) {
            String mime = part.mimeType();
            boolean inline = part.base64Data() != null;
            switch (part.category()) {
                case IMAGE -> builder.addContent(inline
                        ? ImageContent.from(part.base64Data(), mime)
                        : ImageContent.from(part.url()));
                case AUDIO -> builder.addContent(inline
                        ? AudioContent.from(part.base64Data(), mime)
                        : AudioContent.from(part.url()));
                case VIDEO -> builder.addContent(inline
                        ? VideoContent.from(part.base64Data(), mime)
                        : VideoContent.from(part.url()));
                case OTHER -> {
                    // Non-viewable types are declined by the tool before staging, so this is
                    // unreachable in practice; guard defensively rather than emit a broken part.
                }
            }
        }

        return builder.build();
    }
}
