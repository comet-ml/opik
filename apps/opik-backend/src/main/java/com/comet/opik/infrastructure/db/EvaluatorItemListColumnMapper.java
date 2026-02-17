package com.comet.opik.infrastructure.db;

import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

public class EvaluatorItemListColumnMapper extends AbstractArgumentFactory<List<EvaluatorItem>>
        implements
            ColumnMapper<List<EvaluatorItem>> {

    private static final TypeReference<List<EvaluatorItem>> TYPE_REFERENCE = new TypeReference<>() {
    };

    public EvaluatorItemListColumnMapper() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(List<EvaluatorItem> value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setObject(position, JsonUtils.writeValueAsString(value));
            }
        };
    }

    @Override
    public List<EvaluatorItem> map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return performMapping(r.getString(columnNumber));
    }

    @Override
    public List<EvaluatorItem> map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return performMapping(r.getString(columnLabel));
    }

    private List<EvaluatorItem> performMapping(String json) {
        if (StringUtils.isBlank(json) || EvaluatorItem.EMPTY_LIST_JSON.equals(json)) {
            return null;
        }
        return JsonUtils.readValue(json, TYPE_REFERENCE);
    }
}
