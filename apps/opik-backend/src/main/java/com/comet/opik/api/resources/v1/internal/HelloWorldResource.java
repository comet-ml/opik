package com.comet.opik.api.resources.v1.internal;

import com.comet.opik.infrastructure.queues.Queue;
import com.comet.opik.infrastructure.queues.QueueProducer;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Internal resource for testing Java to Python RQ integration.
 * This endpoint allows triggering hello world messages to be processed by Python workers.
 */
@Path("/v1/internal/hello-world")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class HelloWorldResource {

    private final @NonNull QueueProducer queueProducer;

    /**
     * Send a hello world message to Python worker via RQ.
     *
     * @param message The message to send (defaults to "Hello from Java!")
     * @return Response with job ID
     */
    @POST
    public Response sendHelloWorld(@QueryParam("message") String message) {
        String messageToSend = message != null ? message : "Hello from Java!";

        log.info("Sending hello world message to Python worker: '{}'", messageToSend);

        return queueProducer.enqueue(Queue.OPTIMIZER_CLOUD, Map.of("message", messageToSend, "wait_seconds", 0))
                .map(jobId -> {
                    log.info("Hello world message enqueued with job ID: '{}'", jobId);
                    return Response.ok(Map.of(
                            "status", "success",
                            "message", "Message enqueued successfully",
                            "jobId", jobId,
                            "queue", Queue.OPTIMIZER_CLOUD.toString(),
                            "sentMessage", messageToSend)).build();
                })
                .onErrorResume(error -> {
                    log.error("Failed to enqueue hello world message", error);
                    return Mono.just(Response.serverError()
                            .entity(Map.of(
                                    "status", "error",
                                    "message", "Failed to enqueue message: " + error.getMessage()))
                            .build());
                })
                .block();
    }

    /**
     * Get the current size of the hello world queue.
     *
     * @return Response with queue size
     */
    @GET
    @Path("/queue-size")
    public Response getQueueSize() {
        log.info("Getting hello world queue size");

        return queueProducer.getQueueSize(Queue.OPTIMIZER_CLOUD.toString())
                .map(size -> {
                    log.info("Hello world queue size: '{}'", size);
                    return Response.ok(Map.of(
                            "queue", Queue.OPTIMIZER_CLOUD.toString(),
                            "size", size)).build();
                })
                .onErrorResume(error -> {
                    log.error("Failed to get queue size", error);
                    return Mono.just(Response.serverError()
                            .entity(Map.of(
                                    "status", "error",
                                    "message", "Failed to get queue size: " + error.getMessage()))
                            .build());
                })
                .block();
    }
}
