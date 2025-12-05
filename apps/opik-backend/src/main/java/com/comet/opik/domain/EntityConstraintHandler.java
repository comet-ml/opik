package com.comet.opik.domain;

import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
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

    private static boolean isConstraintViolation(Throwable cause) {
        return Throwables.getRootCause(cause) instanceof SQLIntegrityConstraintViolationException;
    }

    default T withError(Supplier<EntityAlreadyExistsException> errorProvider) {
        try {
            return wrappedAction().execute();
        } catch (UnableToExecuteStatementException e) {
            if (isConstraintViolation(e.getCause())) {
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
            if (isConstraintViolation(e.getCause())) {
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
            if (isConstraintViolation(e.getCause())) {
                if (times > 0) {
                    log.warn("Retrying due to constraint violation, remaining attempts: '{}'", times);
                    return internalRetry(times - 1, errorProvider);
                }
                throw errorProvider.get();
            } else {
                throw e;
            }
        }
    }

}
