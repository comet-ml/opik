package com.comet.opik.infrastructure.db;

import com.comet.opik.api.ExportParams;
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

public class ExportParamsArgumentFactory extends AbstractArgumentFactory<ExportParams>
        implements
            ColumnMapper<ExportParams> {

    public ExportParamsArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(ExportParams value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setObject(position, new String(JsonUtils.writeValueAsBytes(value)));
            }
        };
    }

    @Override
    public ExportParams map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return performMapping(r.getString(columnNumber));
    }

    @Override
    public ExportParams map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return performMapping(r.getString(columnLabel));
    }

    private ExportParams performMapping(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }

        return JsonUtils.readValue(json, ExportParams.class);
    }
}
