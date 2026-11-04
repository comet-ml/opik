package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.openai.models.responses.ResponseCreateParams;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAI SDK closes the transport it is given once a client built on it is closed, or garbage collected, which
 * runs the same close. Every Responses API client shares one transport, so a client closing must leave its pooled
 * connections to the others. A raw keep-alive server counts the connections made.
 */
class ResponsesApiSharedTransportTest {

    private static final String RESPONSE = """
            {"id":"resp_1","object":"response","created_at":1,"status":"completed","error":null,\
            "incomplete_details":null,"instructions":null,"max_output_tokens":null,"model":"gpt",\
            "parallel_tool_calls":true,"previous_response_id":null,"reasoning":{"effort":null,"summary":null},\
            "store":false,"temperature":1.0,"text":{"format":{"type":"text"}},"tool_choice":"auto","tools":[],\
            "top_p":1.0,"truncation":"disabled","user":null,"metadata":{},"output":[{"type":"message",\
            "id":"msg_1","status":"completed","role":"assistant","content":[{"type":"output_text","text":"ok",\
            "annotations":[]}]}],"usage":{"input_tokens":1,"input_tokens_details":{"cached_tokens":0},\
            "output_tokens":1,"output_tokens_details":{"reasoning_tokens":0},"total_tokens":2}}""";

    /** A raw server answering every request on a connection with {@link #RESPONSE}, keeping it open. */
    private static ServerSocket keepAliveServer(AtomicInteger connections) throws IOException {
        var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    var socket = server.accept();
                    connections.incrementAndGet();
                    Thread.ofVirtual().start(() -> answer(socket));
                } catch (IOException exception) {
                    return;
                }
            }
        });
        return server;
    }

    private static void answer(Socket socket) {
        var body = RESPONSE.getBytes(StandardCharsets.UTF_8);
        try (socket; var out = socket.getOutputStream()) {
            while (readRequest(socket.getInputStream())) {
                out.write("""
                        HTTP/1.1 200 OK\r
                        Content-Type: application/json\r
                        Content-Length: %d\r
                        \r
                        """.formatted(body.length).getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
            }
        } catch (IOException ignored) {
            // the client closed the connection
        }
    }

    /** Reads one request, false when the connection ended instead. */
    private static boolean readRequest(InputStream in) throws IOException {
        var head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int read = in.read();
            if (read == -1) {
                return false;
            }
            head.append((char) read);
        }
        var contentLength = head.toString().lines()
                .filter(line -> line.regionMatches(true, 0, "Content-Length:", 0, 15))
                .map(line -> Integer.parseInt(line.substring(15).trim()))
                .findFirst()
                .orElse(0);
        in.readNBytes(contentLength);
        return true;
    }

    @Test
    @DisplayName("a Responses API client closing leaves the shared transport's connection to the next client")
    void aClosedClientLeavesTheSharedConnectionPool() throws Exception {
        var connections = new AtomicInteger();
        try (var server = keepAliveServer(connections)) {
            var clientConfig = TestConfigUtils.loadConfigTest().getLlmProviderClient();
            var generator = new OpenAIClientGenerator(clientConfig,
                    new OpenAIModule().responsesApiHttpClient(clientConfig), LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);
            var config = LlmProviderClientApiConfig.builder()
                    .apiKey(RandomStringUtils.secure().nextAlphanumeric(20))
                    .baseUrl("http://localhost:%d/v1".formatted(server.getLocalPort()))
                    .build();
            var model = "gpt-%s".formatted(RandomStringUtils.secure().nextAlphanumeric(8));
            var params = ResponseCreateParams.builder().model(model).input("hello").build();
            var expectedConnections = 1;

            var closedClient = generator.newResponsesApiClient(config, model, null, false);
            closedClient.responses().create(params);
            closedClient.close();

            // OpenAIClient has close() but isn't AutoCloseable
            var nextClient = generator.newResponsesApiClient(config, model, null, false);
            try {
                var actualResponse = nextClient.responses().create(params);

                assertThat(actualResponse.id()).isEqualTo("resp_1");
                assertThat(connections).hasValue(expectedConnections);
            } finally {
                nextClient.close();
            }
        }
    }
}
