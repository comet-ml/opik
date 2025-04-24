package com.comet.opik.domain;

import com.comet.opik.api.Guardrail;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;

@ImplementedBy(GuardrailsDAOImpl.class)
public interface GuardrailsDAO {
    Mono<Long> addGuardrails(EntityType entityType, List<Guardrail> guardrails);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class GuardrailsDAOImpl implements GuardrailsDAO {
    private final @NonNull TransactionTemplateAsync asyncTemplate;

    private static final String BULK_INSERT_GUARDRAILS = """
            INSERT INTO guardrails(
                id,
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
                         :id<item.index>,
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

    @Override
    public Mono<Long> addGuardrails(EntityType entityType, List<Guardrail> guardrails) {
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

    private void bindParameters(EntityType entityType, List<Guardrail> guardrails, Statement statement) {
        for (var i = 0; i < guardrails.size(); i++) {
            var guardrailBatchItem = guardrails.get(i);

            statement.bind("id" + i, guardrailBatchItem.id())
                    .bind("entity_type" + i, entityType.getType())
                    .bind("entity_id" + i, guardrailBatchItem.entityId())
                    .bind("secondary_entity_id" + i, guardrailBatchItem.secondaryId())
                    .bind("project_id" + i, guardrailBatchItem.projectId())
                    .bind("name" + i, guardrailBatchItem.name().toString())
                    .bind("result" + i, guardrailBatchItem.result().getResult())
                    .bind("config" + i, getOrDefault(guardrailBatchItem.config()))
                    .bind("details" + i, getOrDefault(guardrailBatchItem.details()));
        }
    }

    private String getOrDefault(JsonNode value) {
        return value != null ? value.toString() : "";
    }
}
