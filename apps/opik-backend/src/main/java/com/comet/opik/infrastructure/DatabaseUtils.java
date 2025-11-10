package com.comet.opik.infrastructure;

import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import io.dropwizard.db.DataSourceFactory;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.stream.Collectors;

@UtilityClass
@Slf4j
public class DatabaseUtils {

    public static final int ANALYTICS_DELETE_BATCH_SIZE = 10000;

    public static DataSourceFactory filterProperties(DataSourceFactory dataSourceFactory) {
        var filteredProperties = dataSourceFactory.getProperties()
                .entrySet()
                .stream()
                .filter(entry -> StringUtils.isNotBlank(entry.getValue()))
                .collect(Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        java.util.Map.Entry::getValue));
        dataSourceFactory.setProperties(filteredProperties);

        return dataSourceFactory;
    }

    /**
     * Handle SQL duplicate constraint violations in the state database (MySQL) with a specific error message.
     *
     * @param operation the database operation to execute
     * @param errorMessage the error message to include in the exception if a duplicate constraint is violated
     * @throws EntityAlreadyExistsException if a duplicate constraint violation occurs
     * @throws UnableToExecuteStatementException if any other SQL exception occurs
     */
    public static void handleStateDbDuplicateConstraint(@NonNull Runnable operation,
            @NonNull String errorMessage) {
        try {
            operation.run();
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                log.debug("Duplicate constraint violation: {}", errorMessage);
                throw new EntityAlreadyExistsException(new ErrorMessage(List.of(errorMessage)));
            } else {
                throw e;
            }
        }
    }
}
