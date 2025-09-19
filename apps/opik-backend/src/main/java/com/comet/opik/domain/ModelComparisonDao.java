package com.comet.opik.domain;

import com.comet.opik.api.ModelComparison;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RegisterRowMapper(ModelComparisonDao.ModelComparisonRowMapper.class)
public interface ModelComparisonDao {

    @SqlQuery("SELECT * FROM model_comparisons WHERE id = :id")
    Optional<ModelComparison> findById(@Bind("id") UUID id);

    @SqlUpdate("""
            INSERT INTO model_comparisons (
                id, name, description, model_ids, dataset_names, filters, 
                created_at, last_updated_at, created_by, last_updated_by
            ) VALUES (
                :id, :name, :description, :modelIds, :datasetNames, :filters,
                :createdAt, :lastUpdatedAt, :createdBy, :lastUpdatedBy
            )
            """)
    @GetGeneratedKeys
    ModelComparison create(@BindBean ModelComparison comparison);

    @SqlUpdate("""
            UPDATE model_comparisons SET
                name = :name,
                description = :description,
                model_ids = :modelIds,
                dataset_names = :datasetNames,
                filters = :filters,
                results = :results,
                last_updated_at = :lastUpdatedAt,
                last_updated_by = :lastUpdatedBy
            WHERE id = :id
            """)
    ModelComparison update(@BindBean ModelComparison comparison);

    @SqlUpdate("DELETE FROM model_comparisons WHERE id = :id")
    void delete(@Bind("id") UUID id);

    @SqlQuery("""
            SELECT * FROM model_comparisons 
            WHERE (:search IS NULL OR name ILIKE '%' || :search || '%' OR description ILIKE '%' || :search || '%')
            ORDER BY 
                CASE WHEN :sorting = 'name_asc' THEN name END ASC,
                CASE WHEN :sorting = 'name_desc' THEN name END DESC,
                CASE WHEN :sorting = 'created_at_asc' THEN created_at END ASC,
                CASE WHEN :sorting = 'created_at_desc' THEN created_at END DESC,
                created_at DESC
            LIMIT :size OFFSET :offset
            """)
    List<ModelComparison> findModelComparisons(
            @Bind("page") int page,
            @Bind("size") int size,
            @Bind("sorting") String sorting,
            @Bind("search") String search,
            @Bind("offset") int offset
    );

    @SqlQuery("""
            SELECT COUNT(*) FROM model_comparisons 
            WHERE (:search IS NULL OR name ILIKE '%' || :search || '%' OR description ILIKE '%' || :search || '%')
            """)
    long countModelComparisons(@Bind("search") String search);

    @SqlQuery("""
            SELECT DISTINCT 
                model_name as name,
                provider,
                COUNT(*) as trace_count
            FROM traces t
            JOIN spans s ON t.id = s.trace_id
            WHERE s.model_name IS NOT NULL
            GROUP BY model_name, provider
            ORDER BY trace_count DESC
            """)
    List<Map<String, Object>> getAvailableModels();

    @SqlQuery("""
            SELECT DISTINCT 
                d.name,
                d.id,
                COUNT(e.id) as experiment_count
            FROM datasets d
            LEFT JOIN experiments e ON d.id = e.dataset_id
            GROUP BY d.name, d.id
            ORDER BY experiment_count DESC
            """)
    List<Map<String, Object>> getAvailableDatasets();

    default ModelComparison.ModelComparisonPage findModelComparisons(
            int page, 
            int size, 
            String sorting, 
            String search
    ) {
        int offset = (page - 1) * size;
        var content = findModelComparisons(page, size, sorting, search, offset);
        var total = countModelComparisons(search);
        
        return new ModelComparison.ModelComparisonPage(
                page,
                size,
                total,
                content,
                List.of("name", "created_at")
        );
    }

    class ModelComparisonRowMapper implements RowMapper<ModelComparison> {
        @Override
        public ModelComparison map(ResultSet rs, StatementContext ctx) throws SQLException {
            return ModelComparison.builder()
                    .id(rs.getObject("id", UUID.class))
                    .name(rs.getString("name"))
                    .description(rs.getString("description"))
                    .modelIds(parseStringList(rs.getString("model_ids")))
                    .datasetNames(parseStringList(rs.getString("dataset_names")))
                    .filters(parseJsonMap(rs.getString("filters")))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                    .createdBy(rs.getString("created_by"))
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .results(parseResults(rs.getString("results")))
                    .build();
        }

        private List<String> parseStringList(String json) {
            if (json == null || json.trim().isEmpty()) {
                return List.of();
            }
            try {
                // Simple JSON array parsing - in production, use proper JSON library
                return List.of(json.replaceAll("[\\[\\]\"]", "").split(","));
            } catch (Exception e) {
                return List.of();
            }
        }

        private Map<String, Object> parseJsonMap(String json) {
            if (json == null || json.trim().isEmpty()) {
                return Map.of();
            }
            try {
                // Simple JSON parsing - in production, use proper JSON library
                return Map.of();
            } catch (Exception e) {
                return Map.of();
            }
        }

        private ModelComparison.ModelComparisonResults parseResults(String json) {
            if (json == null || json.trim().isEmpty()) {
                return null;
            }
            // Parse results JSON - implementation depends on JSON structure
            return null;
        }
    }
}