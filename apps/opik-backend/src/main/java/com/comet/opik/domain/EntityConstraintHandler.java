package com.comet.opik.domain;

import com.comet.opik.api.error.EntityAlreadyExistsException;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.function.Supplier;

interface EntityConstraintHandler<T> {

    static <E> EntityConstraintHandler<E> handle(EntityConstraintAction<E> entityAction) {
        return () -> entityAction;
    }

    interface EntityConstraintAction<T> {
        T execute();
    }

    EntityConstraintAction<T> wrappedAction();

    default T withError(Supplier<EntityAlreadyExistsException> errorProvider) {
        try {
            return wrappedAction().execute();
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw errorProvider.get();
            } else {
                throw e;
            }
        }
    }

}
