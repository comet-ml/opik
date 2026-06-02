package com.comet.opik.domain;

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
import reactor.core.publisher.Mono;

import java.util.List;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;

@ImplementedBy(TraceSummaryDAOImpl.class)
public interface TraceSummaryDAO {
    Mono<Long> batchInsert(List<TraceSummary> summaries);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class TraceSummaryDAOImpl implements TraceSummaryDAO {

    private static final String BULK_INSERT = """
            INSERT INTO trace_summaries(
                id,
                workspace_id,
                project_id,
                summary
            )
            VALUES
                <items:{item |
                    (
                         :id<item.index>,
                         :workspace_id,
                         :project_id<item.index>,
                         :summary<item.index>
                     )
                     <if(item.hasNext)>
                        ,
                     <endif>
                }>
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;

    @Override
    public Mono<Long> batchInsert(@NonNull List<TraceSummary> summaries) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(summaries), "Argument 'summaries' must not be empty");

        return asyncTemplate.nonTransaction(connection -> {
            var template = TemplateUtils.getBatchSql(BULK_INSERT, summaries.size());

            var statement = connection.createStatement(template.render());

            bindParameters(summaries, statement);

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum);
        });
    }

    private void bindParameters(List<TraceSummary> summaries, Statement statement) {
        for (var i = 0; i < summaries.size(); i++) {
            var summary = summaries.get(i);
            statement.bind("id" + i, summary.traceId().toString())
                    .bind("project_id" + i, summary.projectId().toString())
                    .bind("summary" + i, summary.summary());
        }
    }
}
