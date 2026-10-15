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
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads task YAML and validates it. Any invalid task fails the whole load: a task the service would reject must
 * never reach a submit, where it would fail every job it is in.
 */
@Slf4j
@UtilityClass
public class AiLabelTaskLoader {

    public static final String CLASSPATH_DIRECTORY = "ai-labels/tasks";

    static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_]{1,32}");

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // A repeated key would otherwise silently replace the first, e.g. a question or a version.
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** The tasks in {@code tasksDir} when it is set, otherwise the ones bundled on the classpath. */
    public static List<AiLabelTask> load(@NonNull AiLabelsConfig config) {
        var budgets = Budgets.from(config);
        if (StringUtils.isNotBlank(config.getTasksDir())) {
            return loadDirectory(Path.of(config.getTasksDir()), budgets);
        }
        try {
            return loadBundled(Path.of(AiLabelTaskLoader.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI()), budgets);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Lists the directory inside this class's own jar or classes directory rather than through a class loader lookup,
     * which would miss a jar written without directory entries and would let another classpath root shadow it.
     */
    static List<AiLabelTask> loadBundled(Path codeSource, Budgets budgets) {
        if (Files.isDirectory(codeSource)) {
            return loadDirectory(codeSource.resolve(CLASSPATH_DIRECTORY), budgets);
        }
        try (var jar = FileSystems.newFileSystem(codeSource)) {
            return loadDirectory(jar.getPath(CLASSPATH_DIRECTORY), budgets);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to open ai-labels tasks, jar '%s'".formatted(codeSource),
                    exception);
        }
    }

    static List<AiLabelTask> loadDirectory(Path directory, Budgets budgets) {
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
            throw new UncheckedIOException("failed to list ai-labels tasks, directory '%s'".formatted(directory),
                    exception);
        }
        var tasks = new ArrayList<AiLabelTask>();
        for (var file : files) {
            try (var input = Files.newInputStream(file)) {
                tasks.add(parse(file.getFileName().toString(), input, budgets));
            } catch (IOException exception) {
                throw new UncheckedIOException("failed to read ai-labels task, file '%s'".formatted(file), exception);
            }
        }
        checkUniqueNames(tasks);
        log.info("Loaded ai-labels tasks, directory '{}', tasks '{}'", directory,
                tasks.stream().map(AiLabelTask::name).toList());
        return List.copyOf(tasks);
    }

    static AiLabelTask parse(String source, InputStream input, Budgets budgets) {
        AiLabelTask task;
        try {
            task = YAML_MAPPER.readValue(input, AiLabelTask.class);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("invalid ai-labels task, file '%s'".formatted(source), exception);
        }
        try {
            validate(task, budgets);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("invalid ai-labels task, file '%s': %s".formatted(source,
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
        require(NAME_PATTERN.matcher(task.name()).matches(), "name must match %s", NAME_PATTERN);
        validateVersions(task);
        switch (task.endpoint()) {
            case DECIDE -> validateDecide(task, budgets);
            case GENERATE -> validateGenerate(task, budgets);
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

    private static void validateDecide(AiLabelTask task, Budgets budgets) {
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
        long promptBytes = InferenceJson.writeBytes(prefixed).length;
        requireFits(promptBytes, budgets);
    }

    private static void validateGenerate(AiLabelTask task, Budgets budgets) {
        require(task.questions() == null, "a generate task has a prompt and schema, no questions");
        require(task.prompt() != null, "a generate task needs a prompt");
        require(task.schema() != null && task.schema().isObject(), "a generate task needs an object schema");
        var user = task.prompt().user();
        require(StringUtils.countMatches(user, AiLabelTask.STATE_PLACEHOLDER) == 1,
                "%s must appear exactly once in prompt.user", AiLabelTask.STATE_PLACEHOLDER);
        var system = task.prompt().system();
        require(system == null || !system.contains(AiLabelTask.STATE_PLACEHOLDER),
                "%s must not appear in prompt.system", AiLabelTask.STATE_PLACEHOLDER);
        var maxTokens = task.effectiveMaxTokens();
        require(maxTokens >= BatchGenerateItem.MIN_MAX_TOKENS && maxTokens <= BatchGenerateItem.MAX_MAX_TOKENS,
                "max_tokens must be between %s and %s", BatchGenerateItem.MIN_MAX_TOKENS,
                BatchGenerateItem.MAX_MAX_TOKENS);
        var temperature = task.effectiveTemperature();
        require(temperature >= BatchGenerateItem.MIN_TEMPERATURE && temperature <= BatchGenerateItem.MAX_TEMPERATURE,
                "temperature must be between %s and %s", BatchGenerateItem.MIN_TEMPERATURE,
                BatchGenerateItem.MAX_TEMPERATURE);
        JsonSchemaSubset.validate(task.schema());
        long promptBytes = utf8Length(system)
                + utf8Length(user.replace(AiLabelTask.STATE_PLACEHOLDER, ""));
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
