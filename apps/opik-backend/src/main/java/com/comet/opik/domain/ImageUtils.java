package com.comet.opik.domain;

import org.stringtemplate.v4.ST;

public class ImageUtils {
    public static final String PREFIX_JPEG = "/9j/";
    public static final String PREFIX_PNG = "iVBORw0KGgo=";
    public static final String PREFIX_GIF0 = "R0lGODlh";
    public static final String PREFIX_GIF1 = "R0lGODdh";
    public static final String PREFIX_BMP = "Qk";
    public static final String PREFIX_TIFF0 = "SUkqAA";
    public static final String PREFIX_TIFF1 = "II*";
    private static final String IMAGE_CHARS = "[^\"]+";
    private static final String IMAGE_TRUNCATION_REGEX = "data:image/[^;]{3,4};base64," + IMAGE_CHARS + "|"
            + PREFIX_JPEG + IMAGE_CHARS + "|"
            + PREFIX_PNG + IMAGE_CHARS + "|"
            + PREFIX_GIF0 + IMAGE_CHARS + "|"
            + PREFIX_GIF1 + IMAGE_CHARS + "|"
            + PREFIX_BMP + IMAGE_CHARS + "|"
            + PREFIX_TIFF0 + IMAGE_CHARS + "|"
            + PREFIX_TIFF1 + IMAGE_CHARS;

    public static ST addTruncateToTemplate(ST template, boolean truncate) {
        return template.add("truncate", truncate ? ImageUtils.IMAGE_TRUNCATION_REGEX : null);
    }
}
