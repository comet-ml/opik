package com.comet.opik.domain;

import com.comet.opik.api.GuardrailBatchItem;
import com.comet.opik.api.GuardrailsCheck;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;

@ImplementedBy(GuardrailsDAOImpl.class)
public interface GuardrailsDAO {
    Mono<Long> addGuardrails(EntityType entityType, List<GuardrailBatchItem> guardrails);

    Flux<GuardrailsCheck> getTraceGuardrails(String workspaceId, EntityType entityType, UUID entityId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class GuardrailsDAOImpl implements GuardrailsDAO {
    private final @NonNull TransactionTemplateAsync asyncTemplate;

    private static final String BULK_INSERT_GUARDRAILS = """
            INSERT INTO guardrails(
                entity_type,
                entity_id,
                secondary_entity_id,
                project_id,
                workspace_id,
                name,
                result,
                config,
                details,
                created_by,
                last_updated_by
            )
            VALUES
                <items:{item |
                    (
                         :entity_type<item.index>,
                         :entity_id<item.index>,
                         :secondary_entity_id<item.index>,
                         :project_id<item.index>,
                         :workspace_id,
                         :name<item.index>,
                         :result<item.index>,
                         :config<item.index>,
                         :details<item.index>,
                         :user_name,
                         :user_name
                     )
                     <if(item.hasNext)>
                        ,
                     <endif>
                }>
            ;
            """;

    private static final String SELECT_GUARDRAILS_BY_ID = """
            SELECT
                *
            FROM guardrails
            WHERE entity_id = :entity_id
            AND entity_type = :entity_type
            AND workspace_id = :workspace_id
            ORDER BY entity_id DESC, last_updated_at DESC
            LIMIT 1 BY entity_id, name
            ;
            """;

    @Override
    public Mono<Long> addGuardrails(EntityType entityType, List<GuardrailBatchItem> guardrails) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(guardrails),
                "Argument 'guardrails' must not be empty");

        return asyncTemplate.nonTransaction(connection -> {

            ST template = TemplateUtils.getBatchSql(BULK_INSERT_GUARDRAILS, guardrails.size());

            var statement = connection.createStatement(template.render());

            bindParameters(entityType, guardrails, statement);

            return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement))
                    .flatMap(Result::getRowsUpdated)
                    .reduce(Long::sum);
        });
    }

    @Override
    public Flux<GuardrailsCheck> getTraceGuardrails(String workspaceId, EntityType entityType, UUID entityId) {
        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(SELECT_GUARDRAILS_BY_ID);

            statement
                    .bind("workspace_id", workspaceId)
                    .bind("entity_id", entityId)
                    .bind("entity_type", entityType.getType());

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> mapGuardrail(row)));
        });
    }

    private void bindParameters(EntityType entityType, List<GuardrailBatchItem> guardrails, Statement statement) {
        for (var i = 0; i < guardrails.size(); i++) {

            var guardrailBatchItem = guardrails.get(i);

            statement.bind("entity_type" + i, entityType.getType())
                    .bind("entity_id" + i, guardrailBatchItem.id())
                    .bind("secondary_entity_id" + i, guardrailBatchItem.secondaryId())
                    .bind("project_id" + i, guardrailBatchItem.projectId())
                    .bind("name" + i, guardrailBatchItem.name())
                    .bind("result" + i, guardrailBatchItem.result().getResult())
                    .bind("config" + i, getOrDefault(guardrailBatchItem.config()))
                    .bind("details" + i, getOrDefault(guardrailBatchItem.details()));
        }
    }

    private String getOrDefault(JsonNode value) {
        return value != null ? value.toString() : "";
    }

    private GuardrailsCheck mapGuardrail(Row row) {
        return GuardrailsCheck.builder()
                .name(row.get("name", String.class))
                .result(GuardrailResult.fromString(row.get("result", String.class)))
                .config(Optional.ofNullable(row.get("config", String.class))
                        .filter(it -> !it.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .details(Optional.ofNullable(row.get("details", String.class))
                        .filter(it -> !it.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build();
    }
}
