package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.inference.Question;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AiLabelTaskLoaderTest {

    private static final AiLabelTaskLoader.Budgets BUDGETS = new AiLabelTaskLoader.Budgets(80_000, 100_000);

    /** The decide example of spec 04. */
    private static final String DECIDE = """
            name: agent_outcome
            endpoint: decide
            cache: false
            version: 2
            applies_from: 2026-11-01T00:00:00Z
            history:
              - {version: 1, applies_from: 2026-10-20T00:00:00Z}
            questions:
              succeeded: {type: noul, instructions: "Did the agent accomplish the user's goal?"}
              topic:     {type: choice, instructions: "Main topic?", criteria: {billing: null, technical: null, other: null}}
              quality:   {type: score, instructions: "How good?", criteria: [poor, fair, good]}
            """;

    private static final String GENERATE = """
            name: summary
            endpoint: generate
            version: 1
            applies_from: 2026-11-01T00:00:00Z
            prompt:
              system: "You summarize agent traces."
              user: "Summarize this trace:\\n{{state}}"
            schema: {type: object, properties: {summary: {type: string}}, required: [summary]}
            max_tokens: 256
            temperature: 0
            """;

    @Test
    void decideTaskLoads() {
        var task = parse(DECIDE);

        assertThat(task.name()).isEqualTo("agent_outcome");
        assertThat(task.endpoint()).isEqualTo(AiLabelEndpoint.DECIDE);
        assertThat(task.cache()).isFalse();
        assertThat(task.version()).isEqualTo(2);
        assertThat(task.appliesFrom()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(task.history()).containsExactly(new AiLabelTask.Version(1, Instant.parse("2026-10-20T00:00:00Z")));
        assertThat(task.questions()).containsOnlyKeys("succeeded", "topic", "quality");
        assertThat(task.questions().get("topic")).isInstanceOf(Question.ChoiceQuestion.class);
        assertThat(task.questions().get("quality")).isInstanceOf(Question.ScoreQuestion.class);
    }

    @Test
    void generateTaskLoadsWithCacheOffByDefault() {
        var task = parse(GENERATE);

        assertThat(task.endpoint()).isEqualTo(AiLabelEndpoint.GENERATE);
        assertThat(task.cache()).isFalse();
        assertThat(task.history()).isEmpty();
        assertThat(task.effectiveMaxTokens()).isEqualTo(256);
        assertThat(task.effectiveTemperature()).isZero();
    }

    static Stream<Arguments> invalidTasks() {
        return Stream.of(
                arguments("name with upper case", DECIDE.replace("name: agent_outcome", "name: Agent_Outcome"),
                        "name must match"),
                arguments("name over 32 characters", DECIDE.replace("agent_outcome", "a".repeat(33)),
                        "name must match"),
                arguments("version not above history", DECIDE.replace("version: 2", "version: 1"),
                        "greater than every history version"),
                arguments("duplicate history version", DECIDE.replace("version: 2", "version: 3").replace(
                        "  - {version: 1, applies_from: 2026-10-20T00:00:00Z}",
                        "  - {version: 1, applies_from: 2026-10-20T00:00:00Z}\n"
                                + "  - {version: 1, applies_from: 2026-10-21T00:00:00Z}"),
                        "history versions must be unique"),
                arguments("version missing", DECIDE.replace("version: 2\n", ""), "version must be at least 1"),
                arguments("applies_from missing", DECIDE.replace("applies_from: 2026-11-01T00:00:00Z\n", ""),
                        "appliesFrom"),
                arguments("history applies_from missing",
                        DECIDE.replace("{version: 1, applies_from: 2026-10-20T00:00:00Z}", "{version: 1}"),
                        "appliesFrom"),
                arguments("unknown key", DECIDE + "sampling: 0.5\n", "sampling"),
                arguments("decide without questions", DECIDE.substring(0, DECIDE.indexOf("questions:")),
                        "1 to 32 questions"),
                arguments("decide with 33 questions", DECIDE.substring(0, DECIDE.indexOf("questions:"))
                        + questions(33), "1 to 32 questions"),
                arguments("question id too long once prefixed",
                        DECIDE.replace("succeeded:", "s".repeat(51) + ":"), "must match"),
                arguments("question id with a space", DECIDE.replace("succeeded:", "\"did succeed\":"),
                        "must match"),
                arguments("choice without options",
                        DECIDE.replace("{billing: null, technical: null, other: null}", "{}"),
                        "1 to '255' options"),
                arguments("score with one level", DECIDE.replace("[poor, fair, good]", "[poor]"),
                        "'2' to '10' levels"),
                arguments("empty instructions",
                        DECIDE.replace("\"Did the agent accomplish the user's goal?\"", "\"\""),
                        "instructions must not be empty"),
                arguments("unknown question type", DECIDE.replace("type: noul", "type: yesno"), "yesno"),
                arguments("unknown question key", DECIDE.replace("type: noul,", "type: noul, weight: 2,"),
                        "weight"),
                arguments("decide with a prompt", DECIDE + "max_tokens: 10\n", "questions only"),
                arguments("generate without placeholder", GENERATE.replace("\\n{{state}}", ""),
                        "exactly once in prompt.user"),
                arguments("generate with placeholder twice", GENERATE.replace("{{state}}", "{{state}} {{state}}"),
                        "exactly once in prompt.user"),
                arguments("generate with placeholder in system",
                        GENERATE.replace("You summarize agent traces.", "{{state}}"), "must not appear"),
                arguments("max_tokens 0", GENERATE.replace("max_tokens: 256", "max_tokens: 0"),
                        "max_tokens must be between"),
                arguments("max_tokens 4097", GENERATE.replace("max_tokens: 256", "max_tokens: 4097"),
                        "max_tokens must be between"),
                arguments("temperature above 2", GENERATE.replace("temperature: 0", "temperature: 2.5"),
                        "temperature must be between"),
                arguments("negative temperature", GENERATE.replace("temperature: 0", "temperature: -0.1"),
                        "temperature must be between"),
                arguments("schema with an unsupported keyword",
                        GENERATE.replace("{type: string}", "{type: string, not: {const: x}}"),
                        "unsupported keywords [not]"),
                arguments("generate without schema", GENERATE.replace(
                        "schema: {type: object, properties: {summary: {type: string}}, required: [summary]}\n", ""),
                        "needs an object schema"),
                arguments("generate with questions", GENERATE + "questions: {q: {type: noul, instructions: x}}\n",
                        "no questions"),
                arguments("prompt plus state over budget",
                        GENERATE.replace("Summarize this trace:", "x".repeat(20_000)),
                        "must be under modelInputBudgetBytes"),
                arguments("questions plus state over budget",
                        DECIDE.replace("Main topic?", "x".repeat(20_000)),
                        "must be under modelInputBudgetBytes"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTasks")
    void invalidTaskIsRejected(String description, String yaml, String reason) {
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid ai-labels task, file 'test.yaml'")
                .satisfies(exception -> assertThat(fullMessage(exception)).contains(reason));
    }

    @Test
    void duplicateNamesAcrossFilesAreRejected(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("a.yaml"), GENERATE);
        Files.writeString(directory.resolve("b.yml"), GENERATE);

        assertThatThrownBy(() -> AiLabelTaskLoader.loadDirectory(directory, BUDGETS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate ai-labels task name, name 'summary'");
    }

    @Test
    void shippedTasksLoad() {
        var tasks = AiLabelTaskLoader.load(new AiLabelsConfig());

        assertThat(tasks).extracting(AiLabelTask::name).contains("trace_summary");
    }

    @Test
    void bundledTasksLoadFromAJarWithoutDirectoryEntries(@TempDir Path directory) throws IOException {
        var jar = directory.resolve("opik-backend.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("ai-labels/tasks/summary.yaml"));
            output.write(GENERATE.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("ai-labels/tasks/notes.txt"));
            output.closeEntry();
        }

        var tasks = AiLabelTaskLoader.loadBundled(jar, BUDGETS);

        assertThat(tasks).extracting(AiLabelTask::name).containsExactly("summary");
    }

    @Test
    void tasksDirReplacesTheShippedTasks(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("agent_outcome.yaml"), DECIDE);
        Files.writeString(directory.resolve("summary.yaml"), GENERATE);
        Files.writeString(directory.resolve("README.md"), "not a task");
        var config = new AiLabelsConfig();
        config.setTasksDir(directory.toString());
        config.setEnabled(true);
        var registry = new AiLabelTaskRegistry(config);

        registry.start();

        assertThat(registry.tasks()).extracting(AiLabelTask::name).containsExactly("agent_outcome", "summary");
        assertThat(registry.get("summary").endpoint()).isEqualTo(AiLabelEndpoint.GENERATE);
        assertThat(registry.find("trace_summary")).isEmpty();
    }

    @Test
    void startFailsOnAnInvalidTaskWhenEnabled(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("bad.yaml"), GENERATE.replace("max_tokens: 256", "max_tokens: 0"));
        var config = new AiLabelsConfig();
        config.setTasksDir(directory.toString());
        config.setEnabled(true);

        assertThatThrownBy(() -> new AiLabelTaskRegistry(config).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("file 'bad.yaml'");
    }

    @Test
    void startLoadsNothingWhenDisabled(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("bad.yaml"), "not: a task");
        var config = new AiLabelsConfig();
        config.setTasksDir(directory.toString());

        new AiLabelTaskRegistry(config).start();
    }

    private static AiLabelTask parse(String yaml) {
        return AiLabelTaskLoader.parse("test.yaml",
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), BUDGETS);
    }

    private static String questions(int count) {
        return "questions:\n" + IntStream.range(0, count)
                .mapToObj(index -> "  q%d: {type: noul, instructions: \"Q%d?\"}".formatted(index, index))
                .collect(Collectors.joining("\n")) + "\n";
    }

    private static String fullMessage(Throwable exception) {
        return Stream.iterate(exception, Objects::nonNull, Throwable::getCause)
                .map(Throwable::getMessage)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" | "));
    }
}
