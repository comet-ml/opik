package com.comet.opik.domain;

import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.google.common.base.Preconditions;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.function.Supplier;

public interface EntityConstraintHandler<T> {

    Logger log = LoggerFactory.getLogger(EntityConstraintHandler.class);

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

    default T onErrorDo(Supplier<T> errorProvider) {
        try {
            return wrappedAction().execute();
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                return errorProvider.get();
            } else {
                throw e;
            }
        }
    }

    default T withRetry(int times, Supplier<EntityAlreadyExistsException> errorProvider) {
        Preconditions.checkArgument(times > 0, "Retry times must be greater than 0");

        return internalRetry(times, errorProvider);
    }

    private T internalRetry(int times, Supplier<EntityAlreadyExistsException> errorProvider) {
        try {
            return wrappedAction().execute();
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                if (times > 0) {
                    log.warn("Retrying due to constraint violation, remaining attempts: {}", times);
                    return internalRetry(times - 1, errorProvider);
                }
                throw errorProvider.get();
            } else {
                throw e;
            }
        }
    }

}
