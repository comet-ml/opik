package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Reads Bedrock's {@code application/vnd.amazon.eventstream} body, which is binary frames rather than SSE: a prelude
 * (total length, headers length, CRC), typed headers, a JSON payload and a CRC of the whole frame. Each event frame is
 * passed on as a {@link ServerSentEvent} named by its {@code :event-type} header, so the stream runs on the same
 * guarded HTTP client as every other provider.
 */
class AwsEventStreamParser implements ServerSentEventParser {

    static final String MESSAGE_TYPE = ":message-type";
    static final String EVENT_TYPE = ":event-type";
    static final String EXCEPTION_TYPE = ":exception-type";
    static final String ERROR_CODE = ":error-code";
    static final String ERROR_MESSAGE = ":error-message";

    private static final int PRELUDE_LENGTH = 12;
    private static final int CRC_LENGTH = 4;
    // AWS caps one event-stream message at 16 MB.
    private static final int MAX_MESSAGE_LENGTH = 16 * 1024 * 1024;

    private static final byte STRING_HEADER = 7;

    @Override
    public void parse(@NonNull InputStream body, @NonNull ServerSentEventListener listener) {
        try {
            while (true) {
                var prelude = body.readNBytes(PRELUDE_LENGTH);
                if (prelude.length == 0) {
                    return;
                }
                if (prelude.length < PRELUDE_LENGTH) {
                    throw new IOException("Truncated event stream prelude");
                }
                var preludeBuffer = ByteBuffer.wrap(prelude);
                int totalLength = preludeBuffer.getInt();
                int headersLength = preludeBuffer.getInt();
                int preludeCrc = preludeBuffer.getInt();
                if (crc(prelude, 0, 8) != preludeCrc) {
                    throw new IOException("Event stream prelude CRC mismatch");
                }
                if (headersLength < 0 || totalLength < PRELUDE_LENGTH + headersLength + CRC_LENGTH
                        || totalLength > MAX_MESSAGE_LENGTH) {
                    throw new IOException("Invalid event stream message length " + totalLength);
                }
                var rest = body.readNBytes(totalLength - PRELUDE_LENGTH);
                if (rest.length < totalLength - PRELUDE_LENGTH) {
                    throw new IOException("Truncated event stream message");
                }
                var messageCrc = new CRC32();
                messageCrc.update(prelude);
                messageCrc.update(rest, 0, rest.length - CRC_LENGTH);
                if ((int) messageCrc.getValue() != ByteBuffer.wrap(rest, rest.length - CRC_LENGTH, CRC_LENGTH)
                        .getInt()) {
                    throw new IOException("Event stream message CRC mismatch");
                }

                var headers = stringHeaders(ByteBuffer.wrap(rest, 0, headersLength));
                var payload = new String(rest, headersLength, rest.length - headersLength - CRC_LENGTH,
                        StandardCharsets.UTF_8);
                if ("event".equals(headers.get(MESSAGE_TYPE))) {
                    listener.onEvent(new ServerSentEvent(headers.get(EVENT_TYPE), payload));
                    continue;
                }
                listener.onError(toException(headers, payload));
                return;
            }
        } catch (IOException exception) {
            listener.onError(exception);
        }
    }

    // An exception frame carries the same {"message": ...} body as a non-2xx response, so both reach callers as an
    // HttpException with the status that response would have had.
    private static HttpException toException(Map<String, String> headers, String payload) {
        var type = headers.getOrDefault(EXCEPTION_TYPE, headers.getOrDefault(ERROR_CODE, ""));
        var body = payload.isBlank()
                ? "{\"message\": \"%s\"}".formatted(headers.getOrDefault(ERROR_MESSAGE, type))
                : payload;
        return new HttpException(statusOf(type), body);
    }

    private static int statusOf(String exceptionType) {
        return switch (exceptionType) {
            case "validationException" -> 400;
            case "accessDeniedException" -> 403;
            case "resourceNotFoundException" -> 404;
            case "throttlingException" -> 429;
            case "serviceUnavailableException" -> 503;
            default -> 500;
        };
    }

    private static Map<String, String> stringHeaders(ByteBuffer buffer) throws IOException {
        var headers = new HashMap<String, String>();
        while (buffer.hasRemaining()) {
            var name = new String(bytes(buffer, Byte.toUnsignedInt(buffer.get())), StandardCharsets.UTF_8);
            byte type = buffer.get();
            switch (type) {
                case 0, 1 -> {
                }
                case 2 -> skip(buffer, 1);
                case 3 -> skip(buffer, 2);
                case 4 -> skip(buffer, 4);
                case 5, 8 -> skip(buffer, 8);
                case 6 -> skip(buffer, Short.toUnsignedInt(buffer.getShort()));
                case STRING_HEADER -> headers.put(name,
                        new String(bytes(buffer, Short.toUnsignedInt(buffer.getShort())), StandardCharsets.UTF_8));
                case 9 -> skip(buffer, 16);
                default -> throw new IOException("Unknown event stream header type " + type);
            }
        }
        return headers;
    }

    private static byte[] bytes(ByteBuffer buffer, int length) {
        var bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    private static void skip(ByteBuffer buffer, int length) {
        buffer.position(buffer.position() + length);
    }

    private static int crc(byte[] bytes, int offset, int length) {
        var crc = new CRC32();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }
}
