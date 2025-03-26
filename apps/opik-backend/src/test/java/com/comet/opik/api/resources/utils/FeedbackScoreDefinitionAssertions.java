package com.comet.opik.api.resources.utils;

import jakarta.ws.rs.core.Response;

import static org.assertj.core.api.Assertions.assertThat;

public class FeedbackScoreDefinitionAssertions {

    public static void assertErrorResponse(Response actualResponse, String message, int expectedStatus) {

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        assertThat(actualResponse.hasEntity()).isTrue();
        assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                .isEqualTo(message);
    }
}
