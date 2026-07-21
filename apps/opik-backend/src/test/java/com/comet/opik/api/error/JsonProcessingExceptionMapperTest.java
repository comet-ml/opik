package com.comet.opik.api.error;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonMappingException;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JsonProcessingExceptionMapper Unit Test")
class JsonProcessingExceptionMapperTest {

    private static final String DOC_LENGTH_MESSAGE = "Document length (12590880) exceeds the maximum allowed "
            + "(12582912, from `StreamReadConstraints.getMaxDocumentLength()`)";

    @Mock
    private IngestionSizeGuardMetrics sizeGuardMetrics;
    @Mock
    private Provider<RequestContext> requestContext;
    @Mock
    private Provider<UriInfo> uriInfoProvider;
    @Mock
    private UriInfo uriInfo;

    private JsonProcessingExceptionMapper mapper() {
        return new JsonProcessingExceptionMapper(sizeGuardMetrics, requestContext, uriInfoProvider);
    }

    // A size-guard trip is a payload-too-large rejection -> 413 (consistent with RequestSizeLimitFilter).
    private void assertPayloadTooLarge(Response response) {
        assertThat(response.getStatus()).isEqualTo(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
        assertThat(((ErrorMessage) response.getEntity()).getMessage()).startsWith("Unable to process JSON.");
    }

    // Genuine malformed JSON stays 400.
    private void assertBadRequest(Response response) {
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        assertThat(((ErrorMessage) response.getEntity()).getMessage()).startsWith("Unable to process JSON.");
    }

    @Test
    @DisplayName("records the metric and returns 413 when the constraint is WRAPPED by a JsonMappingException")
    void recordsMetricForWrappedStreamConstraint() {
        // The real-world case: Jackson trips maxDocumentLength while binding Span["output"] and re-wraps
        // the parser-level StreamConstraintsException in a JsonMappingException (with the reference chain).
        // Before the cause-chain walk, a plain instanceof check missed this and the metric under-counted.
        when(uriInfoProvider.get()).thenReturn(uriInfo);
        var streamConstraint = new StreamConstraintsException(DOC_LENGTH_MESSAGE);
        JsonProcessingException wrapped = new JsonMappingException(null,
                DOC_LENGTH_MESSAGE + " (through reference chain: com.comet.opik.api.SpanBatch[\"spans\"]"
                        + "->java.util.ArrayList[0]->com.comet.opik.api.Span[\"output\"])",
                streamConstraint);

        var response = mapper().toResponse(wrapped);

        // The UNWRAPPED constraint is handed to the metric so it can classify document vs string length.
        verify(sizeGuardMetrics).recordStreamConstraintRejection(streamConstraint, uriInfo, requestContext);
        assertPayloadTooLarge(response);
    }

    @Test
    @DisplayName("records the metric and returns 413 for a raw (unwrapped) StreamConstraintsException")
    void recordsMetricForRawStreamConstraint() {
        when(uriInfoProvider.get()).thenReturn(uriInfo);
        var streamConstraint = new StreamConstraintsException(DOC_LENGTH_MESSAGE);

        var response = mapper().toResponse(streamConstraint);

        verify(sizeGuardMetrics).recordStreamConstraintRejection(streamConstraint, uriInfo, requestContext);
        assertPayloadTooLarge(response);
    }

    @Test
    @DisplayName("finds a StreamConstraintsException nested deeper than the immediate cause (413)")
    void recordsMetricForDeeplyNestedStreamConstraint() {
        when(uriInfoProvider.get()).thenReturn(uriInfo);
        var streamConstraint = new StreamConstraintsException(DOC_LENGTH_MESSAGE);
        var middle = new JsonMappingException(null, "wrap 1", streamConstraint);
        JsonProcessingException outer = new JsonMappingException(null, "wrap 2", middle);

        var response = mapper().toResponse(outer);

        verify(sizeGuardMetrics).recordStreamConstraintRejection(streamConstraint, uriInfo, requestContext);
        assertPayloadTooLarge(response);
    }

    @Test
    @DisplayName("does NOT record the metric and returns 400 for genuinely malformed JSON (no size constraint)")
    void doesNotRecordForPlainMalformedJson() {
        JsonProcessingException malformed = new JsonMappingException(null, "Unexpected end-of-input",
                new IllegalStateException("boom"));

        var response = mapper().toResponse(malformed);

        verifyNoInteractions(sizeGuardMetrics);
        assertBadRequest(response);
    }

    @Test
    @DisplayName("does not loop on a self-referencing cause chain (400)")
    void doesNotLoopOnSelfReferencingCause() {
        // A malformed chain whose cause is itself must terminate the walk, not hang.
        var selfReferencing = new JsonProcessingException("self-referencing") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        var response = mapper().toResponse(selfReferencing);

        verifyNoInteractions(sizeGuardMetrics);
        assertBadRequest(response);
    }
}
