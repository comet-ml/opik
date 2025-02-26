package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReactServiceErrorResponse(String msg, int code) {
    public static final String NOT_ALLOWED_TO_ACCESS_WORKSPACE = "User is not allowed to access workspace";
    public static final String MISSING_WORKSPACE = "Workspace name should be provided";
    public static final String MISSING_API_KEY = "API key should be provided";
}
