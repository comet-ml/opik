package com.comet.opik.domain.ailabels;

import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.inference.BatchDecideItem;
import com.comet.opik.infrastructure.inference.BatchGenerateItem;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.Question;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads task YAML and validates it. Any invalid task fails the whole load: a task the service would reject must
 * never reach a submit, where it would fail every job it is in.
 */
@Slf4j
@UtilityClass
public class AiLabelTaskLoader {

    static final String AI_LABELS = "ai-labels";
    public static final String CLASSPATH_DIRECTORY = tasksDirectory(AI_LABELS);

    static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // A repeated key would otherwise silently replace the first, e.g. a question or a version.
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            // One task per file: a second YAML document would otherwise be ignored.
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** A feature's bundled task folder: {@code <label>/tasks}, as ai-labels' own. */
    private static String tasksDirectory(String label) {
        return label + "/tasks";
    }

    /**
     * How one set of task files is checked: {@code label} names the set in errors and logs, {@code placeholders} gives
     * each task's placeholder rule by task name (null for a name the set does not know), and {@code budgets}, when not
     * null, is the model input the prompt must fit with a full-budget state.
     */
    record Rules(@NonNull String label, @NonNull Function<String, TaskPlaceholders> placeholders,
            @Nullable Budgets budgets) {

        static Rules aiLabels(Budgets budgets) {
            return new Rules(AI_LABELS, name -> TaskPlaceholders.AI_LABELS, budgets);
        }
    }

    /**
     * The generate tasks bundled under {@code <label>/tasks} for a feature other than ai-labels, which fills its own
     * placeholders: {@code placeholders} gives each task's rule by name, and a file whose task it does not name fails.
     * The ai-labels model input budget does not apply: the feature sizes its own prompts.
     */
    public static List<AiLabelTask> loadBundled(@NonNull String label,
            @NonNull Map<String, TaskPlaceholders> placeholders) {
        try {
            return loadBundled(codeSource(), new Rules(label, placeholders::get, null));
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The tasks in {@code tasksDir} when it is set, otherwise the ones bundled on the classpath. */
    public static List<AiLabelTask> load(@NonNull AiLabelsConfig config) {
        var budgets = Budgets.from(config);
        if (StringUtils.isNotBlank(config.getTasksDir())) {
            return loadDirectory(Path.of(config.getTasksDir()), budgets);
        }
        try {
            return loadBundled(codeSource(), budgets);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path codeSource() throws URISyntaxException {
        return Path.of(AiLabelTaskLoader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /**
     * Lists the directory inside this class's own jar or classes directory rather than through a class loader lookup,
     * which would miss a jar written without directory entries and would let another classpath root shadow it.
     */
    static List<AiLabelTask> loadBundled(Path codeSource, Budgets budgets) {
        return loadBundled(codeSource, Rules.aiLabels(budgets));
    }

    static List<AiLabelTask> loadBundled(Path codeSource, Rules rules) {
        var classpathDirectory = tasksDirectory(rules.label());
        if (Files.isDirectory(codeSource)) {
            return loadDirectory(codeSource.resolve(classpathDirectory), rules);
        }
        try (var jar = FileSystems.newFileSystem(codeSource)) {
            return loadDirectory(jar.getPath(classpathDirectory), rules);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to open %s tasks, jar '%s'".formatted(rules.label(), codeSource),
                    exception);
        }
    }

    static List<AiLabelTask> loadDirectory(Path directory, Budgets budgets) {
        return loadDirectory(directory, Rules.aiLabels(budgets));
    }

    static List<AiLabelTask> loadDirectory(Path directory, Rules rules) {
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing
                    .filter(path -> {
                        var fileName = path.getFileName().toString();
                        return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to list %s tasks, directory '%s'".formatted(rules.label(),
                    directory), exception);
        }
        var tasks = new ArrayList<AiLabelTask>();
        for (var file : files) {
            try (var input = Files.newInputStream(file)) {
                tasks.add(parse(file.getFileName().toString(), input, rules));
            } catch (IOException exception) {
                throw new UncheckedIOException("failed to read %s task, file '%s'".formatted(rules.label(), file),
                        exception);
            }
        }
        checkUniqueNames(tasks);
        log.info("Loaded {} tasks, directory '{}', tasks '{}'", rules.label(), directory,
                tasks.stream().map(AiLabelTask::name).toList());
        return List.copyOf(tasks);
    }

    static AiLabelTask parse(String source, InputStream input, Budgets budgets) {
        return parse(source, input, Rules.aiLabels(budgets));
    }

    static AiLabelTask parse(String source, InputStream input, Rules rules) {
        AiLabelTask task;
        try {
            task = YAML_MAPPER.readValue(input, AiLabelTask.class);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("invalid %s task, file '%s'".formatted(rules.label(), source), exception);
        }
        try {
            validate(task, rules);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid %s task, file '%s': %s".formatted(rules.label(), source,
                    exception.getMessage()), exception);
        }
        return task;
    }

    static void checkUniqueNames(List<AiLabelTask> tasks) {
        var names = new HashSet<String>();
        for (var task : tasks) {
            if (!names.add(task.name())) {
                throw new IllegalStateException("duplicate ai-labels task name, name '%s'".formatted(task.name()));
            }
        }
    }

    static void validate(AiLabelTask task, Budgets budgets) {
        validate(task, Rules.aiLabels(budgets));
    }

    static void validate(AiLabelTask task, Rules rules) {
        require(NAME_PATTERN.matcher(task.name()).matches(), "name must match %s", NAME_PATTERN);
        validateVersions(task);
        switch (task.endpoint()) {
            case DECIDE -> validateDecide(task, rules.budgets());
            case GENERATE -> {
                var placeholders = rules.placeholders().apply(task.name());
                require(placeholders != null, "no %s task is named '%s'", rules.label(), task.name());
                validateGenerate(task, placeholders, rules.budgets());
            }
        }
    }

    private static void validateVersions(AiLabelTask task) {
        require(task.version() >= 1, "version must be at least 1");
        var seen = new HashSet<Integer>();
        for (var earlier : task.history()) {
            require(earlier.version() >= 1, "history versions must be at least 1");
            require(earlier.version() < task.version(), "version must be greater than every history version");
            require(seen.add(earlier.version()), "history versions must be unique");
        }
    }

    private static void validateDecide(AiLabelTask task, @Nullable Budgets budgets) {
        require(task.prompt() == null && task.schema() == null && task.maxTokens() == null
                && task.temperature() == null, "a decide task has questions only, no prompt, schema or parameters");
        require(task.questionCount() >= 1 && task.questionCount() <= BatchDecideItem.MAX_QUESTIONS,
                "a decide task has 1 to %s questions", BatchDecideItem.MAX_QUESTIONS);
        task.questions().forEach((question, definition) -> {
            var id = task.questionId(question);
            require(Question.ID_PATTERN.matcher(id).matches(), "question id '%s' must match %s", id,
                    Question.ID_PATTERN);
            try {
                definition.validate();
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("question '%s': %s".formatted(question, exception.getMessage()),
                        exception);
            }
        });
        var prefixed = new TreeMap<String, Question>();
        task.questions().forEach((question, definition) -> prefixed.put(task.questionId(question), definition));
        if (budgets != null) {
            requireFits(InferenceJson.writeBytes(prefixed).length, budgets);
        }
    }

    private static void validateGenerate(AiLabelTask task, TaskPlaceholders placeholders, @Nullable Budgets budgets) {
        require(task.questions() == null, "a generate task has a prompt and schema, no questions");
        require(task.prompt() != null, "a generate task needs a prompt");
        require(task.schema() != null && task.schema().isObject(), "a generate task needs an object schema");
        var user = task.prompt().user();
        placeholders.required().stream().sorted().forEach(placeholder -> require(
                StringUtils.countMatches(user, placeholder) == 1, "%s must appear exactly once in prompt.user",
                placeholder));
        placeholders.optional().stream().sorted().forEach(placeholder -> require(
                StringUtils.countMatches(user, placeholder) <= 1, "%s must appear at most once in prompt.user",
                placeholder));
        placeholders.repeated().stream().sorted().forEach(placeholder -> require(user.contains(placeholder),
                "%s must appear in prompt.user", placeholder));
        var system = task.prompt().system();
        placeholders.all().forEach(placeholder -> require(system == null || !system.contains(placeholder),
                "%s must not appear in prompt.system", placeholder));
        var maxTokens = task.effectiveMaxTokens();
        require(maxTokens >= BatchGenerateItem.MIN_MAX_TOKENS && maxTokens <= BatchGenerateItem.MAX_MAX_TOKENS,
                "max_tokens must be between %s and %s", BatchGenerateItem.MIN_MAX_TOKENS,
                BatchGenerateItem.MAX_MAX_TOKENS);
        var temperature = task.effectiveTemperature();
        require(temperature >= BatchGenerateItem.MIN_TEMPERATURE && temperature <= BatchGenerateItem.MAX_TEMPERATURE,
                "temperature must be between %s and %s", BatchGenerateItem.MIN_TEMPERATURE,
                BatchGenerateItem.MAX_TEMPERATURE);
        JsonSchemaSubset.validate(task.schema());
        if (budgets == null) {
            return;
        }
        // History is fitted inside renderBudgetBytes along with the state, so the check is the same with or without it.
        long promptBytes = utf8Length(system) + utf8Length(user.replace(AiLabelTask.STATE_PLACEHOLDER, "")
                .replace(AiLabelTask.HISTORY_PLACEHOLDER, ""));
        requireFits(promptBytes, budgets);
    }

    /** The task's own text plus a full-budget state must fit the model input. */
    private static void requireFits(long promptBytes, Budgets budgets) {
        require(promptBytes + budgets.renderBudgetBytes() < budgets.modelInputBudgetBytes(),
                "prompt (%s bytes) plus renderBudgetBytes (%s) must be under modelInputBudgetBytes (%s)", promptBytes,
                budgets.renderBudgetBytes(), budgets.modelInputBudgetBytes());
    }

    private static long utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void require(boolean condition, String message, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException(message.formatted(args));
        }
    }

    record Budgets(long renderBudgetBytes, long modelInputBudgetBytes) {

        static Budgets from(AiLabelsConfig config) {
            return new Budgets(config.getRenderBudgetBytes(), config.getModelInputBudgetBytes());
        }
    }
}
