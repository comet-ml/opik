package com.comet.opik.domain;

import com.comet.opik.api.error.EntityAlreadyExistsException;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.BatchUpdateException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityConstraintHandlerTest {

    private static final Supplier<EntityAlreadyExistsException> ENTITY_ALREADY_EXISTS = () -> new EntityAlreadyExistsException(
            new ErrorMessage(409, "Entity already exists"));

    @Test
    void testWithError() {
        EntityConstraintHandler<String> handler = EntityConstraintHandler.handle(() -> {
            throwDuplicateEntryException();
            return null;
        });

        assertThrows(EntityAlreadyExistsException.class, () -> handler.withError(ENTITY_ALREADY_EXISTS));
    }

    @Test
    void testWithError__whenBatchUpdateException__thenThrowEntityAlreadyExistsException() {
        EntityConstraintHandler<String> handler = EntityConstraintHandler.handle(() -> {
            throwBatchDuplicateEntryException();
            return null;
        });

        assertThrows(EntityAlreadyExistsException.class, () -> handler.withError(ENTITY_ALREADY_EXISTS));
    }

    private static void throwDuplicateEntryException() {
        throw new UnableToExecuteStatementException(new SQLIntegrityConstraintViolationException(
                "Duplicate entry '1' for key 'PRIMARY'"), Mockito.mock(StatementContext.class));
    }

    private static void throwBatchDuplicateEntryException() {
        // Simulates the exception chain from @SqlBatch: UnableToExecuteStatementException -> BatchUpdateException -> SQLIntegrityConstraintViolationException
        var rootCause = new SQLIntegrityConstraintViolationException("Duplicate entry '1' for key 'PRIMARY'");
        var batchException = new BatchUpdateException("Batch update failed", new int[0]);
        batchException.initCause(rootCause);
        throw new UnableToExecuteStatementException(batchException, Mockito.mock(StatementContext.class));
    }

    @Test
    void testWithRetrySuccess() {
        EntityConstraintHandler<String> handler = EntityConstraintHandler.handle(() -> "Success");

        assertEquals("Success", handler.withRetry(3, ENTITY_ALREADY_EXISTS));
    }

    @Test
    void testWithRetryFailure() {
        EntityConstraintHandler.EntityConstraintAction<String> action = Mockito
                .spy(new EntityConstraintHandler.EntityConstraintAction<String>() {
                    @Override
                    public String execute() {
                        throwDuplicateEntryException();
                        return "";
                    }
                });

        EntityConstraintHandler<String> handler = EntityConstraintHandler.handle(action);

        final int NUM_OF_RETRIES = 3;

        assertThrows(EntityAlreadyExistsException.class,
                () -> handler.withRetry(NUM_OF_RETRIES, ENTITY_ALREADY_EXISTS));
        Mockito.verify(action, Mockito.times(NUM_OF_RETRIES + 1)).execute();
    }

    @Test
    void testWithRetryExhausted() {
        EntityConstraintHandler.EntityConstraintAction<String> action = Mockito
                .spy(new EntityConstraintHandler.EntityConstraintAction<String>() {
                    @Override
                    public String execute() {
                        throwDuplicateEntryException();
                        return "";
                    }
                });

        EntityConstraintHandler<String> handler = EntityConstraintHandler.handle(action);

        assertThrows(EntityAlreadyExistsException.class, () -> handler.withRetry(1, ENTITY_ALREADY_EXISTS));
        Mockito.verify(action, Mockito.times(2)).execute();
    }

    @Test
    void testWithRetryNonConstraintViolation() {
        EntityConstraintHandler<String> handler = EntityConstraintHandler.handle(() -> {
            throw new UnableToExecuteStatementException(new RuntimeException(), Mockito.mock(StatementContext.class));
        });

        assertThrows(UnableToExecuteStatementException.class, () -> handler.withRetry(3, ENTITY_ALREADY_EXISTS));
    }
}
