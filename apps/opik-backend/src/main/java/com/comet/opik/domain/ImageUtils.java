package com.comet.opik.domain;

import org.stringtemplate.v4.ST;

public class ImageUtils {
    private static final String IMAGE_TRUNCATION_REGEX = "(data:image/[^;]{3,4};base64,)[^\"]+|/9j/[^\"]+";

    public static ST addTruncateToTemplate(ST template, boolean truncate) {
        return template.add("truncate", truncate ? ImageUtils.IMAGE_TRUNCATION_REGEX : null);
    }
}
