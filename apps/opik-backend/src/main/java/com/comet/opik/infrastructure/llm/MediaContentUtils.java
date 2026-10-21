package com.comet.opik.infrastructure.llm;

import com.comet.opik.domain.ImageUtils;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.VideoContent;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

// langchain4j-google-genai (Vertex AI) reads a media URI's MIME type from its path, and a data: URI has no path,
// so it throws a NullPointerException. Handing over base64 data with an explicit MIME type avoids that code path.
@UtilityClass
public class MediaContentUtils {

    private static final String DATA_SCHEME = "data:";
    private static final String BASE64_PARAMETER = "base64";
    private static final Pattern BASE64_PATTERN = Pattern.compile(ImageUtils.IMAGE_CHARS);

    // A dataset cell can hold an image as bare base64, which the UI shows as an image because of these magic
    // prefixes. Sent as-is it would be read as a relative URL, so it is wrapped like a data: URI instead.
    private static final Map<String, String> RAW_BASE64_IMAGE_MIME_TYPES = Map.of(
            ImageUtils.PREFIX_JPEG, "image/jpeg",
            ImageUtils.PREFIX_PNG, "image/png",
            ImageUtils.PREFIX_GIF0, "image/gif",
            ImageUtils.PREFIX_GIF1, "image/gif",
            ImageUtils.PREFIX_BMP, "image/bmp",
            ImageUtils.PREFIX_TIFF0, "image/tiff",
            ImageUtils.PREFIX_TIFF1, "image/tiff",
            ImageUtils.PREFIX_WEBP, "image/webp");

    @Builder(toBuilder = true)
    public record Base64DataUri(@NonNull String mimeType, @NonNull String base64Data) {
    }

    public static ImageContent toImageContent(@NonNull String uri) {
        return parseBase64DataUri(uri)
                .or(() -> parseRawBase64Image(uri))
                .map(dataUri -> ImageContent.from(dataUri.base64Data(), dataUri.mimeType()))
                .orElseGet(() -> ImageContent.from(uri));
    }

    public static AudioContent toAudioContent(@NonNull String uri) {
        return parseBase64DataUri(uri)
                .map(dataUri -> AudioContent.from(dataUri.base64Data(), dataUri.mimeType()))
                .orElseGet(() -> AudioContent.from(uri));
    }

    public static VideoContent toVideoContent(@NonNull String uri) {
        return parseBase64DataUri(uri)
                .map(dataUri -> VideoContent.from(dataUri.base64Data(), dataUri.mimeType()))
                .orElseGet(() -> VideoContent.from(uri));
    }

    public static String toUriString(URI uri, String mimeType, String base64Data) {
        return uri != null ? uri.toString() : DATA_SCHEME + mimeType + ";" + BASE64_PARAMETER + "," + base64Data;
    }

    public static Optional<Base64DataUri> parseBase64DataUri(@NonNull String uri) {
        if (!StringUtils.startsWithIgnoreCase(uri, DATA_SCHEME)) {
            return Optional.empty();
        }
        int comma = uri.indexOf(',');
        if (comma < 0) {
            return Optional.empty();
        }
        String[] header = uri.substring(DATA_SCHEME.length(), comma).split(";");
        String mimeType = header[0].trim().toLowerCase(Locale.ROOT);
        boolean isBase64 = header.length > 1 && BASE64_PARAMETER.equalsIgnoreCase(header[header.length - 1].trim());
        if (!isBase64 || !mimeType.contains("/")) {
            return Optional.empty();
        }
        String base64Data = withoutWhitespace(uri.substring(comma + 1));
        if (base64Data.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Base64DataUri.builder()
                .mimeType(mimeType)
                .base64Data(base64Data)
                .build());
    }

    private static Optional<Base64DataUri> parseRawBase64Image(String value) {
        return RAW_BASE64_IMAGE_MIME_TYPES.entrySet().stream()
                .filter(prefixAndMimeType -> value.startsWith(prefixAndMimeType.getKey()))
                .findFirst()
                .map(prefixAndMimeType -> Base64DataUri.builder()
                        .mimeType(prefixAndMimeType.getValue())
                        .base64Data(withoutWhitespace(value))
                        .build())
                .filter(dataUri -> isBase64(dataUri.base64Data()));
    }

    // Vertex AI's client decodes the payload with Java's strict decoder, which rejects the line breaks that
    // MIME-style encoders insert every 76 characters.
    private static String withoutWhitespace(String base64Data) {
        return StringUtils.containsWhitespace(base64Data) ? StringUtils.deleteWhitespace(base64Data) : base64Data;
    }

    private static boolean isBase64(String value) {
        return value.length() % 4 == 0 && BASE64_PATTERN.matcher(value).matches();
    }
}
