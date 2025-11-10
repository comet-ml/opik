package com.comet.opik.infrastructure.json;

import io.dropwizard.jersey.validation.ValidationErrorMessage;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ValidationErrorMessageBodyWriter Unit Test")
class ValidationErrorMessageBodyWriterUnitTest {

    @Test
    @DisplayName("Should correctly identify writable ValidationErrorMessage types")
    void testIsWriteable() {
        var writer = new ValidationErrorMessageBodyWriter();

        // Should be writable for ValidationErrorMessage class
        assertThat(writer.isWriteable(ValidationErrorMessage.class, ValidationErrorMessage.class, null,
                MediaType.APPLICATION_OCTET_STREAM_TYPE))
                .isTrue();

        // Should not be writable for other classes
        assertThat(writer.isWriteable(String.class, String.class, null, MediaType.APPLICATION_OCTET_STREAM_TYPE))
                .isFalse();
    }

    @Test
    @DisplayName("Should successfully serialize ValidationErrorMessage to JSON bytes")
    void testWriteTo() throws Exception {
        var writer = new ValidationErrorMessageBodyWriter();
        var errorMessage = new ValidationErrorMessage(List.of("Validation error 1", "Validation error 2"));
        var outputStream = new ByteArrayOutputStream();

        // Write the ValidationErrorMessage to the output stream
        writer.writeTo(errorMessage, ValidationErrorMessage.class, ValidationErrorMessage.class, null,
                MediaType.APPLICATION_OCTET_STREAM_TYPE, null, outputStream);

        // Verify the output contains the expected JSON content
        var result = outputStream.toString();
        assertThat(result).isNotEmpty();
        assertThat(result).contains("Validation error 1");
        assertThat(result).contains("Validation error 2");
        assertThat(result).contains("errors");
    }

    @Test
    @DisplayName("Should calculate correct size for ValidationErrorMessage JSON")
    void testGetSize() {
        var writer = new ValidationErrorMessageBodyWriter();
        var errorMessage = new ValidationErrorMessage(List.of("Validation error"));

        // Get the calculated size
        long size = writer.getSize(errorMessage, ValidationErrorMessage.class, ValidationErrorMessage.class,
                null, MediaType.APPLICATION_OCTET_STREAM_TYPE);

        // Verify size is positive and reasonable
        assertThat(size).isGreaterThan(0);
        assertThat(size).isLessThan(1000); // Reasonable upper bound for a simple error message
    }
}
