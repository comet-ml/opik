package com.comet.opik.infrastructure.db;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

public interface TransactionTemplate {

    TxConfig WRITE = new TxConfig().readOnly(false);
    TxConfig READ_ONLY = new TxConfig().readOnly(true);

    interface TransactionCallback<T> {
        Mono<T> execute(Connection handler);
    }

    interface NoTransactionStream<T> {
        Flux<T> execute(Connection handler);
    }

    <T> Mono<T> nonTransaction(TransactionCallback<T> callback);

    <T> Flux<T> stream(NoTransactionStream<T> callback);
}

@RequiredArgsConstructor
class TransactionTemplateImpl implements TransactionTemplate {

    private final ConnectionFactory connectionFactory;

    @Override
    public <T> Mono<T> nonTransaction(TransactionCallback<T> callback) {
        return Mono.from(connectionFactory.create())
                .flatMap(callback::execute);
    }

    @Override
    public <T> Flux<T> stream(NoTransactionStream<T> callback) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(callback::execute);
    }
}
