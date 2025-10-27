package com.comet.opik.domain;

import com.comet.opik.api.GroupByConfig;
import com.comet.opik.api.GroupByType;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;
import java.util.UUID;

@RegisterConstructorMapper(GroupByConfig.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface ChartGroupingDAO {

    @SqlUpdate("INSERT INTO chart_grouping (id, chart_id, group_by_field, group_by_type, limit_top_n) " +
            "VALUES (:id, :chartId, :bean.field, :bean.type, :bean.limitTopN) " +
            "ON DUPLICATE KEY UPDATE " +
            "group_by_field = :bean.field, " +
            "group_by_type = :bean.type, " +
            "limit_top_n = :bean.limitTopN")
    void save(@Bind("id") UUID id,
            @Bind("chartId") UUID chartId,
            @BindMethods("bean") GroupByConfig groupBy);

    @SqlUpdate("UPDATE chart_grouping SET " +
            "group_by_field = COALESCE(:field, group_by_field), " +
            "group_by_type = COALESCE(:type, group_by_type), " +
            "limit_top_n = COALESCE(:limitTopN, limit_top_n) " +
            "WHERE chart_id = :chartId")
    void update(@Bind("chartId") UUID chartId,
            @Bind("field") String field,
            @Bind("type") GroupByType type,
            @Bind("limitTopN") Integer limitTopN);

    @SqlUpdate("DELETE FROM chart_grouping WHERE chart_id = :chartId")
    void deleteByChartId(@Bind("chartId") UUID chartId);

    @SqlQuery("SELECT * FROM chart_grouping WHERE chart_id = :chartId")
    GroupByConfig findByChartId(@Bind("chartId") UUID chartId);

    default Optional<GroupByConfig> fetch(UUID chartId) {
        return Optional.ofNullable(findByChartId(chartId));
    }
}
