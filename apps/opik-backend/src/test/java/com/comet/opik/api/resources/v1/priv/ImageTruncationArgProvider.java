package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.domain.ImageUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;

public class ImageTruncationArgProvider {
    public static Stream<Arguments> provideTestArguments() {
        final String IMAGE_TEMPLATE = """
                        { "messages": [{
                            "role": "user",
                            "content": [{
                            "type": "image_url",
                            "image_url": {
                                "url": "%s",
                                "details": "some details for this image"
                            }
                        }]}] }
                """;
        final String IMAGE_TEMPLATE_MULTIPLE = """
                        { "messages": [{
                            "role": "user",
                            "content": [{
                            "type": "image_url",
                            "image_url": {
                                "url": "%s",
                                "details": "some details for this image"
                            }
                        }, {
                            "type": "image_url",
                            "image_url": {
                                "url": "%s",
                                "details": "some details for this image"
                            }
                        }]}] }
                """;
        final String PREFIX_JPEG_DATA = "data:image/jpg;base64," + ImageUtils.PREFIX_JPEG +
                RandomStringUtils.randomAlphanumeric(100);
        final String NO_PREFIX_JPEG_DATA = ImageUtils.PREFIX_JPEG + RandomStringUtils.randomAlphanumeric(100);
        final String PREFIX_PNG_DATA = "data:image/png;base64," + ImageUtils.PREFIX_PNG +
                RandomStringUtils.randomAlphanumeric(100);
        final String NO_PREFIX_PNG_DATA = ImageUtils.PREFIX_PNG + RandomStringUtils.randomAlphanumeric(100);
        final String PREFIX_GIF_DATA0 = "data:image/gif;base64," + ImageUtils.PREFIX_GIF0 +
                RandomStringUtils.randomAlphanumeric(100);
        final String NO_PREFIX_GIF_DATA0 = ImageUtils.PREFIX_GIF0 + RandomStringUtils.randomAlphanumeric(100);
        final String PREFIX_GIF_DATA1 = "data:image/gif;base64," + ImageUtils.PREFIX_GIF1 +
                RandomStringUtils.randomAlphanumeric(100);
        final String NO_PREFIX_GIF_DATA1 = ImageUtils.PREFIX_GIF1 + RandomStringUtils.randomAlphanumeric(100);
        final String PREFIX_BMP_DATA = "data:image/bmp;base64," + ImageUtils.PREFIX_BMP +
                RandomStringUtils.randomAlphanumeric(100);
        final String NO_PREFIX_BMP_DATA = ImageUtils.PREFIX_BMP + RandomStringUtils.randomAlphanumeric(100);
        final String PREFIX_TIFF_DATA0 = "data:image/tiff;base64," + ImageUtils.PREFIX_TIFF0 +
                RandomStringUtils.randomAlphanumeric(100);
        final String NO_PREFIX_TIFF_DATA0 = ImageUtils.PREFIX_TIFF0 + RandomStringUtils.randomAlphanumeric(100);
        final String PREFIX_TIFF_DATA1 = "data:image/tiff;base64," + ImageUtils.PREFIX_TIFF1 +
                RandomStringUtils.randomAlphanumeric(100);
        final String NO_PREFIX_TIFF_DATA1 = ImageUtils.PREFIX_TIFF1 + RandomStringUtils.randomAlphanumeric(100);
        final String PREFIX_WEBP_DATA = "data:image/webp;base64," + ImageUtils.PREFIX_WEBP +
                RandomStringUtils.randomAlphanumeric(100);
        final String NO_PREFIX_WEBP_DATA = ImageUtils.PREFIX_WEBP + RandomStringUtils.randomAlphanumeric(100);

        final String TRUNCATED_TEXT = "[image]";
        final JsonNode TRUNCATED_MULTIPLE_EXPECTED = JsonUtils.getJsonNodeFromString(
                IMAGE_TEMPLATE_MULTIPLE.formatted(TRUNCATED_TEXT, TRUNCATED_TEXT));
        return Stream.of(
                arguments(
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE.formatted(PREFIX_JPEG_DATA)),
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE.formatted(TRUNCATED_TEXT)),
                        true),
                arguments(
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE.formatted(PREFIX_JPEG_DATA)),
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE.formatted(PREFIX_JPEG_DATA)),
                        false),
                arguments(
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_JPEG_DATA,
                                PREFIX_JPEG_DATA)),
                        TRUNCATED_MULTIPLE_EXPECTED, true),
                arguments(
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_PNG_DATA,
                                PREFIX_PNG_DATA)),
                        TRUNCATED_MULTIPLE_EXPECTED, true),
                arguments(
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_GIF_DATA0,
                                PREFIX_GIF_DATA0)),
                        TRUNCATED_MULTIPLE_EXPECTED, true),
                arguments(
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_GIF_DATA1,
                                PREFIX_GIF_DATA1)),
                        TRUNCATED_MULTIPLE_EXPECTED, true),
                arguments(
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_BMP_DATA,
                                PREFIX_BMP_DATA)),
                        TRUNCATED_MULTIPLE_EXPECTED, true),
                arguments(
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_TIFF_DATA0,
                                PREFIX_TIFF_DATA0)),
                        TRUNCATED_MULTIPLE_EXPECTED, true),
                arguments(
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_TIFF_DATA1,
                                PREFIX_TIFF_DATA1)),
                        TRUNCATED_MULTIPLE_EXPECTED, true),
                arguments(
                        JsonUtils.getJsonNodeFromString(IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_WEBP_DATA,
                                PREFIX_WEBP_DATA)),
                        TRUNCATED_MULTIPLE_EXPECTED, true));
    }
}
