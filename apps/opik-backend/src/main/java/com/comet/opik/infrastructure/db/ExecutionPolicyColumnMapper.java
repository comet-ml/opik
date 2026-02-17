package com.comet.opik.infrastructure.db;

import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.utils.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public class ExecutionPolicyColumnMapper extends AbstractArgumentFactory<ExecutionPolicy>
        implements
            ColumnMapper<ExecutionPolicy> {

    public ExecutionPolicyColumnMapper() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(ExecutionPolicy value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setObject(position, JsonUtils.writeValueAsString(value));
            }
        };
    }

    @Override
    public ExecutionPolicy map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return performMapping(r.getString(columnNumber));
    }

    @Override
    public ExecutionPolicy map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return performMapping(r.getString(columnLabel));
    }

    private ExecutionPolicy performMapping(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JsonUtils.readValue(json, ExecutionPolicy.class);
    }
}
