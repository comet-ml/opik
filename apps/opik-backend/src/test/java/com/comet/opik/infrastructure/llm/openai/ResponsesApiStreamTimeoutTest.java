package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.openai.errors.OpenAIIoException;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Responses API stream runs on the OpenAI SDK's own transport, which gets the same timeouts as every other
 * provider's stream: the read timeout bounds the wait for the headers, then the stream read timeout applies, and no
 * whole-call timeout cuts a long stream. A raw server sends the headers, stays silent, then sends the events.
 */
class ResponsesApiStreamTimeoutTest {

    private static final String EVENTS = """
            event: response.created
            data: {"type":"response.created","sequence_number":0,"response":{"id":"resp_1",\
            "object":"response","created_at":1,"status":"in_progress","error":null,\
            "incomplete_details":null,"instructions":null,"max_output_tokens":null,"model":"gpt",\
            "parallel_tool_calls":true,"previous_response_id":null,"reasoning":{"effort":null,\
            "summary":null},"store":false,"temperature":1.0,"text":{"format":{"type":"text"}},\
            "tool_choice":"auto","tools":[],"top_p":1.0,"truncation":"disabled","user":null,\
            "metadata":{},"output":[],"usage":null}}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","sequence_number":1,"item_id":"msg_1",\
            "output_index":0,"content_index":0,"delta":"ok","logprobs":[]}

            event: response.completed
            data: {"type":"response.completed","sequence_number":2,"response":{"id":"resp_1",\
            "object":"response","created_at":1,"status":"completed","error":null,\
            "incomplete_details":null,"instructions":null,"max_output_tokens":null,"model":"gpt",\
            "parallel_tool_calls":true,"previous_response_id":null,"reasoning":{"effort":null,\
            "summary":null},"store":false,"temperature":1.0,"text":{"format":{"type":"text"}},\
            "tool_choice":"auto","tools":[],"top_p":1.0,"truncation":"disabled","user":null,\
            "metadata":{},"output":[{"type":"message","id":"msg_1","status":"completed",\
            "role":"assistant","content":[{"type":"output_text","text":"ok","annotations":[]}]}],\
            "usage":{"input_tokens":1,"input_tokens_details":{"cached_tokens":0},"output_tokens":1,\
            "output_tokens_details":{"reasoning_tokens":0},"total_tokens":2}}}

            """;

    private static final String RESPONSE = "{\"id\":\"resp_1\",\"object\":\"response\",\"created_at\":1,\"status\":\"completed\",\"error\":null,\"incomplete_details\":null,\"instructions\":null,\"max_output_tokens\":null,\"model\":\"gpt\",\"parallel_tool_calls\":true,\"previous_response_id\":null,\"reasoning\":{\"effort\":null,\"summary\":null},\"store\":false,\"temperature\":1.0,\"text\":{\"format\":{\"type\":\"text\"}},\"tool_choice\":\"auto\",\"tools\":[],\"top_p\":1.0,\"truncation\":\"disabled\",\"user\":null,\"metadata\":{},\"output\":[{\"type\":\"message\",\"id\":\"msg_1\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":1,\"input_tokens_details\":{\"cached_tokens\":0},\"output_tokens\":1,\"output_tokens_details\":{\"reasoning_tokens\":0},\"total_tokens\":2}}";

    /** A raw server answering one call: the headers, then nothing for {@code pause}, then the body. */
    private static ServerSocket pausingServer(String contentType, Duration pause, String body) throws IOException {
        var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept(); var out = socket.getOutputStream()) {
                readRequest(socket.getInputStream());
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                out.write("""
                        HTTP/1.1 200 OK\r
                        Content-Type: %s\r
                        Content-Length: %d\r
                        \r
                        """.formatted(contentType, bytes.length).getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(pause);
                out.write(bytes);
                out.flush();
            } catch (IOException ignored) {
                // the call reports what it got
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        return server;
    }

    private static ServerSocket silentStreamServer(Duration silence) throws IOException {
        var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept(); var out = socket.getOutputStream()) {
                readRequest(socket.getInputStream());
                out.write("""
                        HTTP/1.1 200 OK\r
                        Content-Type: text/event-stream\r
                        Connection: close\r
                        \r
                        """.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(silence);
                out.write(EVENTS.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
                // the call reports what it got
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        return server;
    }

    private static void readRequest(InputStream in) throws IOException {
        var head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int read = in.read();
            if (read == -1) {
                return;
            }
            head.append((char) read);
        }
        var contentLength = head.toString().lines()
                .filter(line -> line.regionMatches(true, 0, "Content-Length:", 0, 15))
                .map(line -> Integer.parseInt(line.substring(15).trim()))
                .findFirst()
                .orElse(0);
        in.readNBytes(contentLength);
    }

    /** What a stream ended with: its chunks, and the error it failed with, if any. */
    private record Outcome(List<ChatCompletionResponse> chunks, Throwable error) {
    }

    private static LlmProviderOpenAiResponses provider(ServerSocket server, String readTimeout,
            String streamReadTimeout) {
        var config = TestConfigUtils.loadConfigTest().getLlmProviderClient();
        config.setConnectTimeout(TestConfigUtils.duration(readTimeout));
        config.setReadTimeout(TestConfigUtils.duration(readTimeout));
        config.setStreamReadTimeout(TestConfigUtils.duration(streamReadTimeout));
        var generator = new OpenAIClientGenerator(config, new OpenAIModule().responsesApiHttpClient(config),
                LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);
        return new LlmProviderOpenAiResponses(generator, LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl("http://localhost:%d/v1".formatted(server.getLocalPort()))
                .build());
    }

    private static Outcome stream(ServerSocket server, String readTimeout, String streamReadTimeout)
            throws Exception {
        // before the fix, the SDK took the connect timeout as both its read and its whole-call timeout
        var provider = provider(server, readTimeout, streamReadTimeout);
        var chunks = new CopyOnWriteArrayList<ChatCompletionResponse>();
        var done = new CompletableFuture<Throwable>();

        provider.generateStream(ChatCompletionRequest.builder().model("gpt-4o").addUserMessage("hello").build(),
                "workspace", chunks::add, () -> done.complete(null), done::complete);

        return new Outcome(chunks, done.get(30, TimeUnit.SECONDS));
    }

    /**
     * A call that isn't a stream keeps the read timeout: a JSON reply pausing for longer than the stream read timeout,
     * but less than the read timeout, completes.
     */
    @Test
    @DisplayName("a Responses API reply that isn't a stream completes on the read timeout, not the stream one")
    void aJsonReplyPausingPastTheStreamReadTimeoutCompletes() throws Exception {
        var pause = Duration.ofSeconds(2);
        try (var server = pausingServer("application/json", pause, RESPONSE)) {
            var provider = provider(server, "5s", "1s");
            var expectedContent = "ok";
            long startNanos = System.nanoTime();

            var actualResponse = provider.generate(
                    ChatCompletionRequest.builder().model("gpt-4o").addUserMessage("hello").build(), "workspace");

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(actualResponse.choices().getFirst().message().content()).isEqualTo(expectedContent);
            assertThat(actualElapsed).isGreaterThanOrEqualTo(pause);
        }
    }

    @Test
    @DisplayName("a Responses API stream silent for less than the stream read timeout completes")
    void aSilentStreamCompletes() throws Exception {
        var silence = Duration.ofSeconds(2);
        try (var server = silentStreamServer(silence)) {
            long startNanos = System.nanoTime();

            var actualOutcome = stream(server, "500ms", "5s");

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(actualOutcome.error()).isNull();
            assertThat(actualOutcome.chunks()).isNotEmpty();
            assertThat(actualElapsed).isGreaterThanOrEqualTo(silence);
        }
    }

    @Test
    @DisplayName("a Responses API stream silent for longer than the stream read timeout fails at it")
    void aStalledStreamFailsAtTheStreamReadTimeout() throws Exception {
        var streamReadTimeout = Duration.ofSeconds(1);
        var expectedMaxElapsed = Duration.ofSeconds(5);
        try (var server = silentStreamServer(Duration.ofSeconds(30))) {
            long startNanos = System.nanoTime();

            var actualOutcome = stream(server, "500ms", "1s");

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            // the SDK's stream failure, over the read that the stream read timeout ended
            assertThat(actualOutcome.error()).isExactlyInstanceOf(OpenAIIoException.class)
                    .cause()
                    .isExactlyInstanceOf(SocketTimeoutException.class)
                    .hasMessage("timeout");
            assertThat(actualElapsed).isBetween(streamReadTimeout, expectedMaxElapsed);
        }
    }
}
