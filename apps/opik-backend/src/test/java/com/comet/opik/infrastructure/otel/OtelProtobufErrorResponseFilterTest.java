package com.comet.opik.infrastructure.otel;

import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A unit test because no error on the OTel endpoint carries a plain-text entity today; the API tests in
 * {@code OpenTelemetryResourceTest} cover the error entities that are re-typed.
 */
@DisplayName("OtelProtobufErrorResponseFilter")
class OtelProtobufErrorResponseFilterTest {

    private static final MediaType PROTOBUF = MediaType.valueOf("application/x-protobuf");

    private final ContainerRequestContext request = mock(ContainerRequestContext.class);
    private final ContainerResponseContext response = mock(ContainerResponseContext.class);
    private final MultivaluedMap<String, Object> responseHeaders = new MultivaluedHashMap<>();

    private final OtelProtobufErrorResponseFilter filter = new OtelProtobufErrorResponseFilter();

    @Test
    @DisplayName("re-types an error entity answering a protobuf request as protobuf")
    void retypesErrorEntity() {
        var error = new ErrorMessage(HttpStatus.SC_UNAUTHORIZED, RandomStringUtils.secure().nextAlphanumeric(20));
        mockProtobufErrorResponse(error.getClass(), MediaType.APPLICATION_JSON_TYPE);

        filter.filter(request, response);

        assertThat(responseHeaders.getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo(PROTOBUF);
    }

    @Test
    @DisplayName("keeps a plain-text entity answering a protobuf request as plain text")
    void keepsPlainTextEntity() {
        mockProtobufErrorResponse(String.class, MediaType.TEXT_PLAIN_TYPE);

        filter.filter(request, response);

        assertThat(responseHeaders.getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo(MediaType.TEXT_PLAIN_TYPE);
    }

    private void mockProtobufErrorResponse(Class<?> entityClass, MediaType responseType) {
        responseHeaders.putSingle(HttpHeaders.CONTENT_TYPE, responseType);
        when(request.getMediaType()).thenReturn(PROTOBUF);
        when(response.getStatus()).thenReturn(HttpStatus.SC_INTERNAL_SERVER_ERROR);
        when(response.hasEntity()).thenReturn(true);
        when(response.getEntityClass()).thenAnswer(invocation -> entityClass);
        when(response.getHeaders()).thenReturn(responseHeaders);
    }
}
