package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("topics tasks")
class TopicTasksTest {

    @Test
    @DisplayName("the bundled author task loads with its placeholders, an object schema and its max tokens")
    void author() {
        var author = new TopicTasks().author();

        assertThat(author.name()).isEqualTo(TopicTasks.AUTHOR);
        assertThat(author.prompt().user()).contains(TopicTasks.CLUSTERS_PLACEHOLDER, TopicTasks.MAX_TOPICS_PLACEHOLDER);
        assertThat(author.schema().get("required")).hasSize(2);
        assertThat(author.effectiveMaxTokens()).isEqualTo(2500);
    }

    @Test
    @DisplayName("the bundled night tasks load with their placeholders, an object schema and their max tokens")
    void night() {
        var tasks = new TopicTasks();

        assertThat(tasks.naming().prompt().user()).contains(TopicTasks.TOPICS_PLACEHOLDER,
                TopicTasks.CLUSTERS_PLACEHOLDER);
        assertThat(tasks.naming().schema().get("required")).hasSize(3);
        assertThat(tasks.namingNew().prompt().user()).contains(TopicTasks.TOPICS_PLACEHOLDER,
                TopicTasks.CLUSTERS_PLACEHOLDER);
        assertThat(tasks.namingNew().schema().get("required")).hasSize(2);
        assertThat(tasks.verify().prompt().user()).contains(TopicTasks.PAIRS_PLACEHOLDER);
        assertThat(tasks.verify().schema().get("required")).hasSize(1);
        assertThat(List.of(tasks.naming(), tasks.namingNew(), tasks.verify()))
                .extracting(AiLabelTask::effectiveMaxTokens).containsExactly(2500, 2500, 60);
    }

    @Test
    @DisplayName("a missing task stops the start")
    void missing() {
        assertThatThrownBy(() -> new TopicTasks(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing topics tasks, names '[author, naming, naming_new, verify]'");
    }
}
