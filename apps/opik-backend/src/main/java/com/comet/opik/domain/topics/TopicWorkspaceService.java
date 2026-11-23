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
 * Whether Topics runs for a workspace: the one place that decides it. A workspace runs Topics only when all three hold:
 * <ul>
 * <li>{@code topics.enabled} is on;</li>
 * <li>it is listed in {@code topics.enabledWorkspaces} ({@code TOPICS_ENABLED_WORKSPACES});</li>
 * <li>it is enabled in {@code ai_label_workspaces}, since Topics reads the ai-labels trace_summary.</li>
 * </ul>
 * The toggle and the list are read from the config on every call, and {@code ai_label_workspaces} only when both
 * pass. Callers check the toggle on its own only where no workspace is involved, such as registering the jobs.
 * {@link #reaches} also checks the workspace's {@link TopicsStage} from the list.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicWorkspaceService {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;

    public List<String> enabledWorkspaceIds() {
        var listed = config.enabledWorkspaceIds();
        if (!config.isEnabled() || listed.isEmpty()) {
            return List.of();
        }
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelWorkspaceDAO.class).enabledWorkspaceIds())
                .stream()
                .filter(listed::contains)
                .toList();
    }

    public boolean isEnabled(@NonNull String workspaceId) {
        return config.isEnabled() && config.enabledWorkspaceIds().contains(workspaceId)
                && template.inTransaction(READ_ONLY,
                        handle -> handle.attach(AiLabelWorkspaceDAO.class).enabled(workspaceId))
                        .orElse(false);
    }

    /** True when the workspace {@link #isEnabled} and its listed stage is {@code stage} or a later one. */
    public boolean reaches(@NonNull String workspaceId, @NonNull TopicsStage stage) {
        var listed = config.enabledWorkspaceStages().get(workspaceId);
        return listed != null && listed.includes(stage) && isEnabled(workspaceId);
    }
}
