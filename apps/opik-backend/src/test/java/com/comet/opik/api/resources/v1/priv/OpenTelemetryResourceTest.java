package com.comet.opik.api.resources.v1.priv;

import io.dropwizard.testing.common.Resource;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.junit.ClassRule;
import org.junit.Test;

public class OpenTelemetryResourceTest {

    @Test
    public void testExportTraceServiceRequest() {
        // Create a dummy ExportTraceServiceRequest.
//        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder().build();
//        byte[] protoBytes = request.toByteArray();
//
//        // Send a POST request with the protobuf payload.
//        Response response = RESOURCES.target("/v1/traces")
//                .request("application/x-protobuf")
//                .post(Entity.entity(protoBytes, "application/x-protobuf"));
//
//        // Assert that the response status is 200 OK.
//        assertEquals("Expected HTTP 200 response", 200, response.getStatus());
    }
}
