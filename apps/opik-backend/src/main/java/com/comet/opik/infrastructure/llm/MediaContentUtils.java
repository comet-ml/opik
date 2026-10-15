package com.comet.opik.infrastructure.llm;

import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.VideoContent;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Optional;

// langchain4j-google-genai (Vertex AI) reads a media URL's MIME type from its path, and a data: URI has no path,
// so it throws a NullPointerException. Handing over base64 data with an explicit MIME type avoids that code path.
@UtilityClass
public class MediaContentUtils {

    private static final String DATA_SCHEME = "data:";
    private static final String BASE64_PARAMETER = "base64";

    @Builder(toBuilder = true)
    public record Base64DataUri(@NonNull String mimeType, @NonNull String base64Data) {
    }

    public static ImageContent toImageContent(@NonNull String url) {
        return parseBase64DataUri(url)
                .map(dataUri -> ImageContent.from(dataUri.base64Data(), dataUri.mimeType()))
                .orElseGet(() -> ImageContent.from(url));
    }

    public static AudioContent toAudioContent(@NonNull String url) {
        return parseBase64DataUri(url)
                .map(dataUri -> AudioContent.from(dataUri.base64Data(), dataUri.mimeType()))
                .orElseGet(() -> AudioContent.from(url));
    }

    public static VideoContent toVideoContent(@NonNull String url) {
        return parseBase64DataUri(url)
                .map(dataUri -> VideoContent.from(dataUri.base64Data(), dataUri.mimeType()))
                .orElseGet(() -> VideoContent.from(url));
    }

    public static Optional<Base64DataUri> parseBase64DataUri(@NonNull String url) {
        if (!StringUtils.startsWithIgnoreCase(url, DATA_SCHEME)) {
            return Optional.empty();
        }
        int comma = url.indexOf(',');
        if (comma < 0) {
            return Optional.empty();
        }
        String[] header = url.substring(DATA_SCHEME.length(), comma).split(";");
        String mimeType = header[0].trim().toLowerCase(Locale.ROOT);
        String base64Data = url.substring(comma + 1);
        boolean isBase64 = header.length > 1 && BASE64_PARAMETER.equalsIgnoreCase(header[header.length - 1].trim());
        if (!isBase64 || !mimeType.contains("/") || base64Data.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Base64DataUri.builder()
                .mimeType(mimeType)
                .base64Data(base64Data)
                .build());
    }
}
