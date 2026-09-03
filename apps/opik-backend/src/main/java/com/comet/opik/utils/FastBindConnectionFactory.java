package com.comet.opik.utils;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Statement;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Applies {@link FastBindStatement} to every statement created on the analytics connection.
 *
 * <p>Wrapping at each call site was tried first and does not hold: only the sites written as
 * {@code createStatement(template.render())} were caught, so bulk inserts that render into a local
 * first - {@code ExperimentItemDAO}, {@code DatasetItemDAO}, {@code TraceThreadDAO},
 * {@code AnnotationQueueDAO} - kept the quadratic named binding, and nothing stopped the next bulk
 * insert from being written the same way. Decorating the factory covers every statement in the
 * backend and makes that class of miss impossible rather than merely fixed.
 */
@RequiredArgsConstructor
public class FastBindConnectionFactory implements ConnectionFactory {

    @Delegate(excludes = CreateOverride.class)
    private final @NonNull ConnectionFactory delegate;

    @Override
    public Publisher<? extends Connection> create() {
        return Mono.from(delegate.create()).map(FastBindConnection::new);
    }

    /** Excluded from {@link Delegate} so the override above is used. */
    private interface CreateOverride {
        Publisher<? extends Connection> create();
    }

    @RequiredArgsConstructor
    private static class FastBindConnection implements Connection {

        @Delegate(excludes = CreateStatementOverride.class)
        private final Connection delegate;

        @Override
        public Statement createStatement(String sql) {
            return FastBindStatement.wrap(delegate.createStatement(sql));
        }

        /** Excluded from {@link Delegate} so the override above is used. */
        private interface CreateStatementOverride {
            Statement createStatement(String sql);
        }
    }
}
