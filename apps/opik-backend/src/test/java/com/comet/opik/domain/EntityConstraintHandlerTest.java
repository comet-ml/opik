package com.comet.opik.domain;

import com.comet.opik.api.error.EntityAlreadyExistsException;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
            fail();
            return null;
        });

        assertThrows(EntityAlreadyExistsException.class, () -> handler.withError(ENTITY_ALREADY_EXISTS));
    }

    private static void fail() {
        throw new UnableToExecuteStatementException(new SQLIntegrityConstraintViolationException(
                "Duplicate entry '1' for key 'PRIMARY'"), Mockito.mock(StatementContext.class));
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
                        fail();
                        return "";
                    }
                });

        EntityConstraintHandler<String> handler = EntityConstraintHandler.handle(action);

        assertThrows(EntityAlreadyExistsException.class, () -> handler.withRetry(3, ENTITY_ALREADY_EXISTS));
        Mockito.verify(action, Mockito.times(4)).execute();
    }

    @Test
    void testWithRetryExhausted() {
        EntityConstraintHandler.EntityConstraintAction<String> action = Mockito
                .spy(new EntityConstraintHandler.EntityConstraintAction<String>() {
                    @Override
                    public String execute() {
                        fail();
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
