package com.comet.opik.infrastructure.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Nullable;
import lombok.Setter;
import org.apache.hc.core5.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

class RemoteAuthTestServer {
    public static int port;

    private static HttpServer server;
    @Setter
    @Nullable private static String responsePayload;
    @Setter
    private static int responseCode = HttpStatus.SC_OK;

    public static void run() throws IOException {
        port = getAvailablePort();
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/auth", new AuthHandler());
        server.setExecutor(null);
        server.start();
    }

    public static void reset() {
        responsePayload = null;
        responseCode = 200;
    }

    public static void stop() {
        server.stop(0);
        reset();
    }

    public static String getServerUrl() {
        return "http://localhost:" + port;
    }

    static class AuthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.getRequestURI();

            if (responsePayload == null) {
                t.sendResponseHeaders(500, 0);
                OutputStream os = t.getResponseBody();
                os.close();
            } else {
                t.getResponseHeaders().set("Content-Type", "application/json");
                t.sendResponseHeaders(responseCode, responsePayload.length());
                OutputStream os = t.getResponseBody();
                os.write(responsePayload.getBytes());
                os.close();
            }
        }
    }

    public static int getAvailablePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        }
    }
}
