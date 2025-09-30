package com.comet.opik.domain.llm;

final class ImageModelSupport {

    private ImageModelSupport() {
    }

    static boolean supportsImageInput(String model) {
        return ModelCapabilities.supportsVision(model);
    }
}
