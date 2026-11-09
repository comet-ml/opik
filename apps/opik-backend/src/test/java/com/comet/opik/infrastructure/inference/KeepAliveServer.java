package com.comet.opik.infrastructure.inference;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Answers every request on a connection with the same keep-alive response, and counts both. */
final class KeepAliveServer implements AutoCloseable {

    private final byte[] response;
    private final ServerSocket serverSocket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    KeepAliveServer(String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var head = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: %d\r\n"
                + "Connection: keep-alive\r\n\r\n").formatted(bytes.length)
                .getBytes(StandardCharsets.US_ASCII);
        response = new byte[head.length + bytes.length];
        System.arraycopy(head, 0, response, 0, head.length);
        System.arraycopy(bytes, 0, response, head.length, bytes.length);
        Thread.ofVirtual().start(this::accept);
    }

    String url() {
        return "http://%s:%d".formatted(serverSocket.getInetAddress().getHostAddress(),
                serverSocket.getLocalPort());
    }

    int connections() {
        return accepted.size();
    }

    int requests() {
        return requests.get();
    }

    private void accept() {
        while (true) {
            try {
                var socket = serverSocket.accept();
                accepted.add(socket);
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException exception) {
                // closed
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (var input = new BufferedInputStream(socket.getInputStream());
                var output = socket.getOutputStream()) {
            while (readRequest(input)) {
                requests.incrementAndGet();
                output.write(response);
                output.flush();
            }
        } catch (IOException exception) {
            // the client closed the connection
        }
    }

    /** Reads one request, its body included; false once the client closes the connection. */
    private static boolean readRequest(BufferedInputStream input) throws IOException {
        var headers = readLine(input);
        if (headers == null) {
            return false;
        }
        long contentLength = 0;
        var chunked = false;
        for (var line = readLine(input); line != null && !line.isEmpty(); line = readLine(input)) {
            var lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-length:")) {
                contentLength = Long.parseLong(lower.substring("content-length:".length()).trim());
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        if (!chunked) {
            input.skipNBytes(contentLength);
            return true;
        }
        for (var size = Long.parseLong(readLine(input).trim(), 16); size > 0; size = Long
                .parseLong(readLine(input).trim(), 16)) {
            input.skipNBytes(size);
            readLine(input);
        }
        readLine(input);
        return true;
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        var line = new StringBuilder();
        for (int c = input.read(); c != -1; c = input.read()) {
            if (c == '\n') {
                return line.toString().stripTrailing();
            }
            line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        for (var socket : accepted) {
            socket.close();
        }
    }
}
