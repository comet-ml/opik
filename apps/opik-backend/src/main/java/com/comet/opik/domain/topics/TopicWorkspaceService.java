package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * The topics opt-in. Topics read the ai-labels trace_summary, so a workspace runs topics only when it is in
 * {@code topics.enabledWorkspaces} (Helm's {@code TOGGLE_TOPICS_WORKSPACES}) and enabled in
 * {@code ai_label_workspaces}: the reads are named for that, eligible rather than enabled. The list is read from the
 * config on every call, and {@code ai_label_workspaces} only when the list is not empty.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicWorkspaceService {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;

    /** The listed workspaces enabled in {@code ai_label_workspaces}, by id. */
    public List<String> eligibleWorkspaceIds() {
        var listed = config.enabledWorkspaceIds();
        if (listed.isEmpty()) {
            return List.of();
        }
        return aiLabelsEnabled().stream().filter(listed::contains).toList();
    }

    private List<String> aiLabelsEnabled() {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelWorkspaceDAO.class).enabledWorkspaceIds());
    }
}
