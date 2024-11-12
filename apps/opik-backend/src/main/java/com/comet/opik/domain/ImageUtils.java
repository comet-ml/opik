package com.comet.opik.domain;

import org.stringtemplate.v4.ST;

public class ImageUtils {
    public static final String PREFIX_JPEG = "/9j/";
    public static final String PREFIX_PNG = "iVBORw0KGgo";
    public static final String PREFIX_GIF0 = "R0lGODlh"; // new version gif (89a)
    public static final String PREFIX_GIF1 = "R0lGODdh"; // old version gif (87a)
    public static final String PREFIX_BMP = "Qk";
    public static final String PREFIX_TIFF0 = "SUkq"; // little endian tiff
    public static final String PREFIX_TIFF1 = "TU0A"; // big endian tiff
    public static final String PREFIX_WEBP = "UklGR";

    private static final String IMAGE_CHARS = "[A-Za-z0-9+/]+={0,2}";
    private static final String IMAGE_TRUNCATION_REGEX =
            // capture images with general base64 prefix in case it is present
            "\"(data:image/[^;]{3,4};base64,)?"
                    // capture images with no base64 prefix but with specific image type prefix
                    + "("
                    + PREFIX_JPEG + "|"
                    + PREFIX_PNG + "|"
                    + PREFIX_GIF0 + "|"
                    + PREFIX_GIF1 + "|"
                    + PREFIX_BMP + "|"
                    + PREFIX_TIFF0 + "|"
                    + PREFIX_TIFF1 + "|"
                    + PREFIX_WEBP
                    + ")"
                    // capture optional base64 padding
                    + "={0,2}"
                    // capture the rest of the image characters
                    + IMAGE_CHARS + "\"";

    public static ST addTruncateToTemplate(ST template, boolean truncate) {
        return template.add("truncate", truncate ? ImageUtils.IMAGE_TRUNCATION_REGEX : null);
    }
}
