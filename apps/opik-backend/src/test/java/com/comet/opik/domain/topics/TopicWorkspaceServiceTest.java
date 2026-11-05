package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.infrastructure.TopicsConfig;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        when(dao.enabledWorkspaceIds()).thenReturn(List.of("a", "b", "c"));
        when(dao.isEnabled(any())).thenAnswer(invocation -> List.of("a", "b", "c")
                .contains(invocation.<String>getArgument(0)));
    }

    @Test
    @DisplayName("eligible: the listed workspaces enabled in ai_label_workspaces, by id")
    void eligible() {
        config.setEnabledWorkspaces("c, x, a");

        assertThat(service.eligibleWorkspaceIds()).containsExactly("a", "c");
        assertThat(service.isEligible("a")).isTrue();
        assertThat(service.isEligible("b")).as("enabled for ai-labels, not listed").isFalse();
        assertThat(service.isEligible("x")).as("listed, not enabled for ai-labels").isFalse();
    }

    @Test
    @DisplayName("mismatched: the listed workspaces not enabled in ai_label_workspaces, by id")
    void mismatched() {
        config.setEnabledWorkspaces("z, a, x");

        assertThat(service.mismatchedWorkspaceIds()).containsExactly("x", "z");
    }

    @Test
    @DisplayName("an empty list reads nothing from MySQL, and no workspace is eligible or mismatched")
    void emptyList() {
        config.setEnabledWorkspaces(" ");

        assertThat(service.eligibleWorkspaceIds()).isEmpty();
        assertThat(service.mismatchedWorkspaceIds()).isEmpty();
        assertThat(service.isEligible("a")).isFalse();
        verify(template, never()).inTransaction(any(TxConfig.class), any());
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
