package com.comet.opik.domain;

import com.comet.opik.api.AssertionStatus;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.template.TemplateUtils;
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
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspace;
import static com.comet.opik.infrastructure.DatabaseUtils.getLogComment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(AssertionResultDAOImpl.class)
public interface AssertionResultDAO {

    Mono<Long> insertBatch(@NonNull EntityType entityType, @NonNull List<? extends FeedbackScoreItem> assertionScores);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AssertionResultDAOImpl implements AssertionResultDAO {

    private static final String BULK_INSERT_ASSERTION_RESULT = """
            INSERT INTO assertion_results(
                entity_type,
                entity_id,
                project_id,
                workspace_id,
                name,
                passed,
                reason,
                source,
                created_by,
                last_updated_by
            )
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (
                         :entity_type<item.index>,
                         :entity_id<item.index>,
                         :project_id<item.index>,
                         :workspace_id,
                         :name<item.index>,
                         :passed<item.index>,
                         :reason<item.index>,
                         :source<item.index>,
                         :user_name,
                         :user_name
                     )
                     <if(item.hasNext)>
                        ,
                     <endif>
                }>
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;

    @Override
    public Mono<Long> insertBatch(@NonNull EntityType entityType,
            @NonNull List<? extends FeedbackScoreItem> assertionScores) {

        Preconditions.checkArgument(CollectionUtils.isNotEmpty(assertionScores),
                "Argument 'assertionScores' must not be empty");

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            var logComment = getLogComment("bulk_insert_assertion_result", workspaceId, assertionScores.size());
            var template = TemplateUtils.getBatchSql(BULK_INSERT_ASSERTION_RESULT, assertionScores.size());
            template.add("log_comment", logComment);

            var statement = connection.createStatement(template.render());

            bindParameters(entityType, assertionScores, statement);
            bindUserNameAndWorkspace(statement, userName, workspaceId);

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(Long::sum);
        }));
    }

    private void bindParameters(EntityType entityType, List<? extends FeedbackScoreItem> scores,
            Statement statement) {
        for (var i = 0; i < scores.size(); i++) {
            var item = scores.get(i);

            statement.bind("entity_type" + i, entityType.getType())
                    .bind("entity_id" + i, item.id())
                    .bind("project_id" + i, item.projectId())
                    .bind("name" + i, item.name())
                    .bind("passed" + i, item.value().compareTo(BigDecimal.ONE) >= 0
                            ? AssertionStatus.PASSED.getValue()
                            : AssertionStatus.FAILED.getValue())
                    .bind("source" + i, item.source().getValue())
                    .bind("reason" + i, getValueOrDefault(item.reason()));
        }
    }

    private String getValueOrDefault(String value) {
        return Optional.ofNullable(value)
                .map(String::trim)
                .filter(StringUtils::isNotEmpty)
                .orElse("");
    }
}
