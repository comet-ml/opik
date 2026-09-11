package com.comet.opik.api.resources.utils;

import com.comet.opik.domain.ImageUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

import static com.comet.opik.utils.JsonUtils.getJsonNodeFromString;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class ImageTruncationArgProvider {
    // templates
    private static final String IMAGE_TEMPLATE = """
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
    private static final String IMAGE_TEMPLATE_MULTIPLE = """
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

    // inputs
    private static final String PREFIX_JPEG_DATA = "data:image/jpg;base64," + ImageUtils.PREFIX_JPEG +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String NO_PREFIX_JPEG_DATA = ImageUtils.PREFIX_JPEG +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String PREFIX_PNG_DATA = "data:image/png;base64," + ImageUtils.PREFIX_PNG +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String NO_PREFIX_PNG_DATA = ImageUtils.PREFIX_PNG +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String PREFIX_GIF_DATA0 = "data:image/gif;base64," + ImageUtils.PREFIX_GIF0 +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String NO_PREFIX_GIF_DATA0 = ImageUtils.PREFIX_GIF0 +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String PREFIX_GIF_DATA1 = "data:image/gif;base64," + ImageUtils.PREFIX_GIF1 +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String NO_PREFIX_GIF_DATA1 = ImageUtils.PREFIX_GIF1 +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String PREFIX_BMP_DATA = "data:image/bmp;base64," + ImageUtils.PREFIX_BMP +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String NO_PREFIX_BMP_DATA = ImageUtils.PREFIX_BMP +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String PREFIX_TIFF_DATA0 = "data:image/tiff;base64," + ImageUtils.PREFIX_TIFF0 +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String NO_PREFIX_TIFF_DATA0 = ImageUtils.PREFIX_TIFF0 +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String PREFIX_TIFF_DATA1 = "data:image/tiff;base64,"
            + ImageUtils.PREFIX_TIFF1 +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String NO_PREFIX_TIFF_DATA1 = ImageUtils.PREFIX_TIFF1 +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String PREFIX_WEBP_DATA = "data:image/webp;base64," + ImageUtils.PREFIX_WEBP +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String NO_PREFIX_WEBP_DATA = ImageUtils.PREFIX_WEBP +
            RandomStringUtils.randomAlphanumeric(100);
    private static final String OPIK_407_REPRODUCE = """
            { "messages": [{
                "role": "user",
                "content": "AI, bolstering the effectiveness and adaptability \\"string\\" to"
                }] }
            """;

    // expected
    private static final String TRUNCATED_TEXT = "[image]";
    private static final JsonNode TRUNCATED_MULTIPLE_EXPECTED = getJsonNodeFromString(
            IMAGE_TEMPLATE_MULTIPLE.formatted(TRUNCATED_TEXT, TRUNCATED_TEXT));

    public static Stream<Arguments> provideTestArguments() {
        return Stream.of(
                arguments(named("single image with prefix", getJsonNodeFromString(
                        IMAGE_TEMPLATE.formatted(PREFIX_JPEG_DATA))),
                        named("truncated", getJsonNodeFromString(IMAGE_TEMPLATE.formatted(TRUNCATED_TEXT))),
                        true),
                arguments(named("single image with prefix", getJsonNodeFromString(
                        IMAGE_TEMPLATE.formatted(PREFIX_JPEG_DATA))),
                        named("not truncated", getJsonNodeFromString(IMAGE_TEMPLATE.formatted(PREFIX_JPEG_DATA))),
                        false),
                arguments(named("multiple jpeg", getJsonNodeFromString(
                        IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_JPEG_DATA, PREFIX_JPEG_DATA))),
                        named("truncated", TRUNCATED_MULTIPLE_EXPECTED), true),
                arguments(named("multiple png", getJsonNodeFromString(
                        IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_PNG_DATA, PREFIX_PNG_DATA))),
                        named("truncated", TRUNCATED_MULTIPLE_EXPECTED), true),
                arguments(named("multiple gif, variant 0", getJsonNodeFromString(
                        IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_GIF_DATA0, PREFIX_GIF_DATA0))),
                        named("truncated", TRUNCATED_MULTIPLE_EXPECTED), true),
                arguments(named("multiple gif, variant 1", getJsonNodeFromString(
                        IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_GIF_DATA1, PREFIX_GIF_DATA1))),
                        named("truncated", TRUNCATED_MULTIPLE_EXPECTED), true),
                arguments(named("multiple bmp", getJsonNodeFromString(
                        IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_BMP_DATA, PREFIX_BMP_DATA))),
                        named("truncated", TRUNCATED_MULTIPLE_EXPECTED), true),
                arguments(named("multiple tiff, variant 0", getJsonNodeFromString(
                        IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_TIFF_DATA0, PREFIX_TIFF_DATA0))),
                        named("truncated", TRUNCATED_MULTIPLE_EXPECTED), true),
                arguments(named("multiple tiff, variant 1", getJsonNodeFromString(
                        IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_TIFF_DATA1, PREFIX_TIFF_DATA1))),
                        named("truncated", TRUNCATED_MULTIPLE_EXPECTED), true),
                arguments(named("multiple webp", getJsonNodeFromString(
                        IMAGE_TEMPLATE_MULTIPLE.formatted(NO_PREFIX_WEBP_DATA, PREFIX_WEBP_DATA))),
                        named("truncated", TRUNCATED_MULTIPLE_EXPECTED), true),
                arguments(named("reproduce opik-407", getJsonNodeFromString(OPIK_407_REPRODUCE)),
                        named("not truncated", getJsonNodeFromString(OPIK_407_REPRODUCE)),
                        true));
    }
}
