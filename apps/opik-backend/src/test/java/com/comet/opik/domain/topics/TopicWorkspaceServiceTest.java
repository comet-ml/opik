package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.infrastructure.TopicsConfig;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;

import java.util.List;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The opt-in rule over a stubbed {@code ai_label_workspaces}; {@code TopicVectorsTickTest} runs it against MySQL.
 */
@DisplayName("topic workspace opt-in")
class TopicWorkspaceServiceTest {

    private final TopicsConfig config = new TopicsConfig();
    private final TransactionTemplate template = mock(TransactionTemplate.class);
    private final AiLabelWorkspaceDAO dao = mock(AiLabelWorkspaceDAO.class);
    private final TopicWorkspaceService service = new TopicWorkspaceService(config, template);

    @BeforeEach
    void setUp() {
        var handle = mock(Handle.class);
        when(handle.attach(AiLabelWorkspaceDAO.class)).thenReturn(dao);
        // The action is the service's own lambda, so only its type can be matched.
        when(template.inTransaction(eq(READ_ONLY), any(TxAction.class)))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        when(dao.enabledWorkspaceIds()).thenReturn(List.of("a", "b", "c"));
    }

    @Test
    @DisplayName("eligible: the listed workspaces enabled in ai_label_workspaces, by id; the list is stripped, blanks "
            + "and repeats dropped")
    void eligible() {
        // b: enabled for ai-labels, not listed; x: listed, not enabled for ai-labels.
        config.setEnabledWorkspaces(" c, x,, a , c ");

        assertThat(service.eligibleWorkspaceIds()).containsExactly("a", "c");
    }

    @Test
    @DisplayName("an empty list reads nothing from MySQL, and no workspace is eligible")
    void emptyList() {
        config.setEnabledWorkspaces(" , ,");

        assertThat(service.eligibleWorkspaceIds()).isEmpty();
        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("the list is read from the config on every call, not cached")
    void listReadOnEveryCall() {
        config.setEnabledWorkspaces("a");
        assertThat(service.eligibleWorkspaceIds()).containsExactly("a");

        config.setEnabledWorkspaces("a,b");

        assertThat(service.eligibleWorkspaceIds()).containsExactly("a", "b");
    }
}
