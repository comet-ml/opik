package com.comet.opik.infrastructure.auth;

import lombok.experimental.UtilityClass;

@UtilityClass
public class CipxTokenUtils {

    public static final String ACCESS_PREFIX = "opik_cipx_at_";

    /**
     * CIPX device tokens are presented bare in {@code Authorization}, following the Opik API-key convention
     * rather than the {@code Bearer} scheme the MCP OAuth tokens use.
     */
    public static boolean isCipxToken(String authHeader) {
        return authHeader != null && authHeader.startsWith(ACCESS_PREFIX);
    }
}
