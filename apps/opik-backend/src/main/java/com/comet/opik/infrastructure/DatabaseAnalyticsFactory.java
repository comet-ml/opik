package com.comet.opik.infrastructure;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Data
public class DatabaseAnalyticsFactory {

    private static final String URL_TEMPLATE = "r2dbc:clickhouse:%s://%s:%s@%s:%d/%s%s";

    private @NotNull Protocol protocol;
    private @NotBlank String host;
    private int port;
    private @NotBlank String username;
    private @NotNull String password;
    private @NotBlank String databaseName;
    private String queryParameters;

    public ConnectionFactory build() {
        var options = queryParameters == null ? "" : "?%s".formatted(queryParameters);
        var url = URL_TEMPLATE.formatted(protocol.getValue(), username, password, host, port, databaseName, options);
        return ConnectionFactories.get(url);
    }

    @RequiredArgsConstructor
    @Getter
    public enum Protocol {
        HTTP("http"),
        HTTPS("https"),
        ;

        private final String value;
    }

}
