package com.comet.opik.domain.cost;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import javax.annotation.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterRowMapper(ModelPriceDAO.ModelPriceRowMapper.class)
@RegisterConstructorMapper(ModelPriceDAO.ListHeader.class)
@RegisterConstructorMapper(ModelPriceDAO.HistoryRow.class)
interface ModelPriceDAO {

    record ListHeader(long version, int rowCount) {
    }

    // A created row has no before, a deleted row no after.
    record HistoryEntry(UUID id, UUID rowId, String action, @Nullable String beforeRow, @Nullable String afterRow) {
    }

    record HistoryRow(long listVersion, String rowId, String action, @Nullable String beforeRow,
            @Nullable String afterRow, Instant createdAt, String createdBy) {
    }

    // Not INSERT IGNORE: on an existing row that takes a shared lock, and two saves holding one each then deadlock on
    // the FOR UPDATE below. ON DUPLICATE KEY UPDATE takes the exclusive lock at once, so the second save waits.
    @SqlUpdate("INSERT INTO model_price_lists (organization_id, created_by, last_updated_by)" +
            " VALUES (:organizationId, :userName, :userName)" +
            " ON DUPLICATE KEY UPDATE version = version")
    void lockOrCreateList(@Bind("organizationId") String organizationId, @Bind("userName") String userName);

    @SqlQuery("SELECT version, row_count FROM model_price_lists WHERE organization_id = :organizationId FOR UPDATE")
    ListHeader lockList(@Bind("organizationId") String organizationId);

    @SqlQuery("SELECT version, row_count FROM model_price_lists WHERE organization_id = :organizationId")
    Optional<ListHeader> findList(@Bind("organizationId") String organizationId);

    @SqlUpdate("UPDATE model_price_lists SET version = :version, row_count = :rowCount, last_updated_by = :userName" +
            " WHERE organization_id = :organizationId")
    void updateList(@Bind("organizationId") String organizationId, @Bind("version") long version,
            @Bind("rowCount") int rowCount, @Bind("userName") String userName);

    @SqlQuery("SELECT id, model_name, provider, kind, input_price, output_price, cache_read_price, cache_write_price," +
            " alias_model_name, alias_provider, note, created_at, created_by, last_updated_at, last_updated_by" +
            " FROM model_prices WHERE organization_id = :organizationId ORDER BY model_name, provider")
    List<ModelPriceRow> findRows(@Bind("organizationId") String organizationId);

    @SqlBatch("INSERT INTO model_prices (id, organization_id, model_name, provider, kind, input_price, output_price," +
            " cache_read_price, cache_write_price, alias_model_name, alias_provider, note, created_by, last_updated_by)"
            +
            " VALUES (:row.id, :organizationId, :row.modelName, :row.provider, :row.kindValue, :row.inputPerMillion," +
            " :row.outputPerMillion, :row.cacheReadPerMillion, :row.cacheWritePerMillion, :row.aliasModelName," +
            " :row.aliasProvider, :row.note, :userName, :userName)")
    void insertRows(@Bind("organizationId") String organizationId, @BindMethods("row") List<ModelPriceRow> rows,
            @Bind("userName") String userName);

    @SqlBatch("UPDATE model_prices SET kind = :row.kindValue, input_price = :row.inputPerMillion," +
            " output_price = :row.outputPerMillion, cache_read_price = :row.cacheReadPerMillion," +
            " cache_write_price = :row.cacheWritePerMillion, alias_model_name = :row.aliasModelName," +
            " alias_provider = :row.aliasProvider, note = :row.note, last_updated_by = :userName" +
            " WHERE id = :row.id AND organization_id = :organizationId")
    void updateRows(@Bind("organizationId") String organizationId, @BindMethods("row") List<ModelPriceRow> rows,
            @Bind("userName") String userName);

    @SqlUpdate("DELETE FROM model_prices WHERE organization_id = :organizationId AND id IN (<ids>)")
    @UseStringTemplateEngine
    void deleteRows(@Bind("organizationId") String organizationId, @BindList("ids") List<UUID> ids);

    @SqlBatch("INSERT INTO model_prices_history (id, organization_id, list_version, row_id, action, before_row," +
            " after_row, created_by)" +
            " VALUES (:entry.id, :organizationId, :listVersion, :entry.rowId, :entry.action, :entry.beforeRow," +
            " :entry.afterRow, :userName)")
    void insertHistory(@Bind("organizationId") String organizationId, @Bind("listVersion") long listVersion,
            @BindMethods("entry") List<HistoryEntry> entries, @Bind("userName") String userName);

    @SqlQuery("SELECT list_version, row_id, action, before_row, after_row, created_at, created_by" +
            " FROM model_prices_history WHERE organization_id = :organizationId" +
            " ORDER BY list_version DESC, created_at DESC, id LIMIT :limit OFFSET :offset")
    List<HistoryRow> findHistory(@Bind("organizationId") String organizationId, @Bind("limit") int limit,
            @Bind("offset") long offset);

    @SqlQuery("SELECT COUNT(*) FROM model_prices_history WHERE organization_id = :organizationId")
    long countHistory(@Bind("organizationId") String organizationId);

    class ModelPriceRowMapper implements RowMapper<ModelPriceRow> {

        @Override
        public ModelPriceRow map(ResultSet rs, StatementContext ctx) throws SQLException {
            return ModelPriceRow.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .modelName(rs.getString("model_name"))
                    .provider(rs.getString("provider"))
                    .kind(CustomModelPrice.Kind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)))
                    .inputPerMillion(rs.getBigDecimal("input_price"))
                    .outputPerMillion(rs.getBigDecimal("output_price"))
                    .cacheReadPerMillion(rs.getBigDecimal("cache_read_price"))
                    .cacheWritePerMillion(rs.getBigDecimal("cache_write_price"))
                    .aliasModelName(rs.getString("alias_model_name"))
                    .aliasProvider(rs.getString("alias_provider"))
                    .note(rs.getString("note"))
                    .createdAt(toInstant(rs.getTimestamp("created_at")))
                    .createdBy(rs.getString("created_by"))
                    .lastUpdatedAt(toInstant(rs.getTimestamp("last_updated_at")))
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .build();
        }

        private static Instant toInstant(Timestamp timestamp) {
            return timestamp == null ? null : timestamp.toInstant();
        }
    }
}
