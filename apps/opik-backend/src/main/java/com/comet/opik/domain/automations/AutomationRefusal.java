package com.comet.opik.domain.automations;

import com.comet.opik.api.error.ErrorMessage;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * A {@code validation_failed} an agent can act on: the body's first error is {@code "<code>: <message>"},
 * and the code is one of the closed set the MCP maps to its own.
 */
@UtilityClass
public class AutomationRefusal {

    public static final String UNKNOWN_EVENT = "unknown_event";
    public static final String UNKNOWN_STEP = "unknown_step";
    public static final String CONDITION_UNKNOWN_STEP = "condition_unknown_step";
    public static final String CONDITION_UNKNOWN_OUTPUT = "condition_unknown_output";
    public static final String TARGET_NOT_FOUND = "target_not_found";
    public static final String LIMIT_REQUIRED = "limit_required";
    public static final String STEP_SCOPE_MISMATCH = "step_scope_mismatch";
    public static final String DUPLICATE_STEP_ID = "duplicate_step_id";
    public static final String UNKNOWN_RECIPE = "unknown_recipe";

    public static ClientErrorException refuse(String code, String message) {
        return new ClientErrorException(Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorMessage(List.of("%s: %s".formatted(code, message))))
                .build());
    }
}
