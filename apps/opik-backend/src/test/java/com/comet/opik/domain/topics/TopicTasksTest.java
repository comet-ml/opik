package com.comet.opik.domain.topics;

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
    @DisplayName("a missing task stops the start")
    void missing() {
        assertThatThrownBy(() -> new TopicTasks(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing topics tasks, names '[author]'");
    }
}
