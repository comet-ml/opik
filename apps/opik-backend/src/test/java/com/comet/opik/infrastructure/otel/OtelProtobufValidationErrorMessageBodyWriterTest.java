package com.comet.opik.infrastructure.otel;

import com.google.rpc.Status;
import io.dropwizard.jersey.validation.ValidationErrorMessage;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A unit test because no API request reaches this writer: its only producer on the OTel endpoint is the
 * {@code @NotNull} violation for a null request, which the test harness never sends (see
 * {@code OpenTelemetryResourceTest.NothingToStore}).
 */
@DisplayName("OtelProtobufValidationErrorMessageBodyWriter")
class OtelProtobufValidationErrorMessageBodyWriterTest {

    private static final MediaType PROTOBUF = MediaType.valueOf("application/x-protobuf");

    private final HttpHeaders requestHeaders = mock(HttpHeaders.class);

    @SuppressWarnings("unchecked")
    private final Provider<HttpHeaders> requestHeadersProvider = mock(Provider.class);

    private final OtelProtobufValidationErrorMessageBodyWriter writer = new OtelProtobufValidationErrorMessageBodyWriter(
            requestHeadersProvider);

    @Test
    @DisplayName("writes a validation error answering a protobuf request as a google.rpc.Status")
    void writesProtobufStatusForProtobufRequest() throws IOException {
        when(requestHeadersProvider.get()).thenReturn(requestHeaders);
        when(requestHeaders.getMediaType()).thenReturn(PROTOBUF);
        var firstError = RandomStringUtils.secure().nextAlphanumeric(20);
        var secondError = RandomStringUtils.secure().nextAlphanumeric(20);
        var error = new ValidationErrorMessage(List.of(firstError, secondError));
        var expectedStatus = Status.newBuilder()
                .setMessage("%s; %s".formatted(firstError, secondError))
                .build();

        assertThat(writer.isWriteable(ValidationErrorMessage.class, ValidationErrorMessage.class, null, PROTOBUF))
                .isTrue();

        var actualBody = new ByteArrayOutputStream();
        writer.writeTo(error, ValidationErrorMessage.class, ValidationErrorMessage.class, null, PROTOBUF,
                new MultivaluedHashMap<>(), actualBody);

        assertThat(Status.parseFrom(actualBody.toByteArray())).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("does not write a validation error answering a JSON request")
    void doesNotWriteForJsonRequest() {
        when(requestHeadersProvider.get()).thenReturn(requestHeaders);
        when(requestHeaders.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);

        assertThat(writer.isWriteable(ValidationErrorMessage.class, ValidationErrorMessage.class, null, PROTOBUF))
                .isFalse();
    }
}
