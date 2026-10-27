package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.net.DestinationGuardDns;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static dev.langchain4j.http.client.sse.ServerSentEventListenerUtils.ignoringExceptions;

/**
 * The custom provider client's transport: OkHttp, whose {@link DestinationGuardDns} checks each address connected to.
 * Otherwise it behaves as the JDK client it replaces: the read timeout bounds the wait for the response headers but
 * not the body, a non-2xx response is an {@link HttpException} carrying its body, and a stream is read on a client
 * thread.
 */
@Slf4j
@RequiredArgsConstructor
class CustomLlmHttpClient implements HttpClient {

    private final @NonNull OkHttpClient client;

    @Override
    public SuccessfulHttpResponse execute(@NonNull HttpRequest request) {
        try (Response response = client.newCall(toOkHttpRequest(request)).execute()) {
            byte[] body = untimedBody(response).bytes();
            if (!response.isSuccessful()) {
                throw new HttpException(response.code(), new String(body, StandardCharsets.UTF_8));
            }
            return toResponse(response, body);
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    @Override
    public void execute(@NonNull HttpRequest request, @NonNull ServerSentEventParser parser,
            @NonNull ServerSentEventListener listener) {
        client.newCall(toOkHttpRequest(request)).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException exception) {
                ignoringExceptions(() -> listener.onError(failure(exception)));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response; InputStream body = untimedBody(response).byteStream()) {
                    if (!response.isSuccessful()) {
                        var exception = new HttpException(response.code(), readErrorBody(body));
                        ignoringExceptions(() -> listener.onError(exception));
                        return;
                    }
                    ignoringExceptions(() -> listener.onOpen(toResponse(response, null)));
                    parser.parse(body, listener);
                    ignoringExceptions(listener::onClose);
                } catch (IOException | RuntimeException exception) {
                    ignoringExceptions(() -> listener.onError(
                            exception instanceof IOException ioException ? failure(ioException) : exception));
                }
            }
        });
    }

    private static Request toOkHttpRequest(HttpRequest request) {
        // parsed as the JDK client did, so a URL it refused is still refused
        var builder = new Request.Builder().url(HttpUrl.get(URI.create(request.url()).toString()));
        request.headers().forEach((name, values) -> {
            if (values != null) {
                values.forEach(value -> builder.addHeader(name, value));
            }
        });
        // as the JDK client did: OkHttp inflates a compressed reply only when it asked for one itself, and an
        // inflated body has no bound, so an uncompressed one is asked for unless the caller chose otherwise
        if (request.headers().keySet().stream().noneMatch(HttpHeaders.ACCEPT_ENCODING::equalsIgnoreCase)) {
            builder.header(HttpHeaders.ACCEPT_ENCODING, "identity");
        }
        RequestBody body = requestBody(request);
        return builder.method(request.method().name(), switch (request.method()) {
            // OkHttp requires a body on POST, where the JDK client sent an empty one
            case POST -> body != null ? body : RequestBody.create(new byte[0]);
            case GET, DELETE -> body;
        }).build();
    }

    private static RequestBody requestBody(HttpRequest request) {
        if (request.formDataFields().isEmpty() && request.formDataFiles().isEmpty()) {
            // no media type on the body: the request's own Content-Type header is sent as is
            return request.body() != null ? RequestBody.create(request.body().getBytes(StandardCharsets.UTF_8)) : null;
        }
        var multipart = new MultipartBody.Builder().setType(MultipartBody.FORM);
        request.formDataFields().forEach(multipart::addFormDataPart);
        request.formDataFiles().forEach((name, file) -> multipart.addFormDataPart(name, file.fileName(),
                RequestBody.create(file.content(), MediaType.parse(file.contentType()))));
        return multipart.build();
    }

    // the JDK client's timeout ended once the headers arrived, so reading the body has none either
    private static ResponseBody untimedBody(Response response) {
        var body = response.body();
        body.source().timeout().clearTimeout();
        return body;
    }

    private static SuccessfulHttpResponse toResponse(Response response, byte[] body) {
        return SuccessfulHttpResponse.builder()
                .statusCode(response.code())
                .headers(response.headers().toMultimap())
                .body(body)
                .build();
    }

    private static String readErrorBody(InputStream body) {
        try {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "Cannot read error response body: " + exception.getMessage();
        }
    }

    private static RuntimeException failure(IOException exception) {
        if (DestinationGuardDns.isRefused(exception)) {
            // no exception detail: it describes a user-supplied URL and how our network resolves it
            log.warn("Refused LLM provider destination");
            return new ProviderRequestRefusedException(ProviderRequestRefusedException.Reason.DESTINATION);
        }
        if (exception instanceof SocketTimeoutException) {
            return new TimeoutException(exception);
        }
        return new RuntimeException(exception);
    }
}
