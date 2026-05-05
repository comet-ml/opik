package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;
import net.thisptr.jackson.jq.exception.JsonQueryException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Drill-in tool for cached entities. Evaluates a jq expression against the
 * full JSON form previously cached by {@link ReadTool} (or pre-seeded for
 * the active trace), in-process via jackson-jq.
 *
 * <p>Args: {@code {type, id, expression}}.
 * <ul>
 *   <li>{@code type} ∈ {trace, span, dataset, dataset_item, project}.
 *       {@code thread} is not supported because {@link ReadTool} cannot cache
 *       a thread entity, so a thread lookup would always cache-miss.</li>
 *   <li>{@code id} entity id (UUID).</li>
 *   <li>{@code expression} a jq expression evaluated against the cached
 *       full JSON.</li>
 * </ul>
 *
 * <p>Returns plain text (not JSON) — matches the Python {@code jq} tool's
 * shape and is denser for the common multi-line jq stdout pattern. See
 * FEATURE_DESIGN_LlmJudgeAgenticTools.md §5.5 for response shapes.
 *
 * <p>Output is capped at {@link #OUTPUT_CAP_CHARS} (~ 4 K tokens) to keep
 * unbounded expressions like {@code .} on a 1 MB trace from blowing up the
 * conversation context.
 *
 * <p><strong>No deadline is wired.</strong> The phase-3 interruption probe
 * (see {@code JqInterruptionProbe}) showed jackson-jq ignores
 * {@code Thread.interrupt()}, so a {@code CompletableFuture}-based timeout
 * would let runaway expressions keep burning CPU after the LLM retries —
 * worse than the bound provided by {@code MAX_TOOL_CALL_ROUNDS} alone.
 */
@Singleton
@Slf4j
public class JqTool implements ToolExecutor {

    public static final String NAME = "jq";

    /** ~ 4 K tokens, matching the Python jq tool's stdout cap. */
    static final int OUTPUT_CAP_CHARS = 16 * 1024;
    static final String OUTPUT_TRUNCATION_HINT = "refine your jq expression to narrow results";

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("Run a jq expression against an already-cached entity (active trace, or any"
                    + " entity previously fetched via the read tool). Use this to extract a specific"
                    + " field, follow a truncation hint (e.g. '[TRUNCATED 4,200 chars — use jq(\\'.spans[2].input\\')"
                    + " to see full]'), or filter spans by a predicate. Returns the jq stdout as text;"
                    + " multi-result expressions render one value per line. Output is capped at 16 KB.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("type",
                            "Entity type: one of trace, span, dataset, dataset_item, project.")
                    .addStringProperty("id", "Entity id (UUID).")
                    .addStringProperty("expression",
                            "jq expression to evaluate against the cached full JSON of the entity.")
                    .required("type", "id", "expression")
                    .build())
            .build();

    /**
     * Root scope is immutable after init and is thread-safe per jackson-jq's
     * docs. Reused across all evaluations so we don't pay the
     * {@code loadFunctions} cost per call.
     */
    private static final Scope ROOT_SCOPE = buildRootScope();

    private static Scope buildRootScope() {
        Scope scope = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, scope);
        return scope;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolSpecification spec() {
        return SPEC;
    }

    @Override
    public String execute(String arguments, TraceToolContext ctx) {
        ParsedArgs args = parseArgs(arguments);
        if (args.error != null) {
            // Argument validation failures are LLM-driven (the model emitted a malformed
            // tool call); keep at debug to avoid log noise but make the bad input
            // discoverable when chasing a specific judge run.
            log.debug("jq tool received invalid arguments: '{}' -> '{}'", arguments, args.error);
            return args.error;
        }
        log.debug("jq tool call with valid arguments: '{}' -> '{}'", arguments, args);

        EntityRef ref = new EntityRef(args.type, args.id);
        Optional<JsonNode> cached = ctx.getCached(ref);
        if (cached.isEmpty()) {
            log.debug("jq tool cache miss for id={}, ref={}", args.id, ref);
            return ToolArgs.cacheMiss(args.type, args.id);
        }

        return evaluate(cached.get(), args);
    }

    private static String evaluate(JsonNode input, ParsedArgs args) {
        JsonQuery query;
        try {
            query = JsonQuery.compile(args.expression, Versions.JQ_1_6);
        } catch (Throwable t) {
            logEvaluationFailure("compile", args, t);
            return errorResponse(args, t);
        }

        var results = new ArrayList<JsonNode>();
        try {
            // Use a child scope per call so any per-call state (e.g. variables set
            // by the expression itself) doesn't leak into the shared root scope.
            Scope childScope = Scope.newChildScope(ROOT_SCOPE);
            query.apply(childScope, input, results::add);
        } catch (Throwable t) {
            logEvaluationFailure("apply", args, t);
            return errorResponse(args, t);
        }

        return successResponse(args, results);
    }

    private static void logEvaluationFailure(String stage, ParsedArgs args, Throwable t) {
        // jq compile / runtime errors are driven by LLM-emitted expressions and are
        // expected to occur; keep them at debug to avoid log noise. Anything else
        // (StackOverflowError, unexpected internals) is a real server-side concern.
        if (t instanceof JsonQueryException) {
            log.debug("jq '{}' failed for '{}:{}' expression='{}': '{}'",
                    stage, args.type, args.id, args.expression, t.getMessage());
        } else {
            log.warn("jq '{}' crashed for '{}:{}' expression='{}'",
                    stage, args.type, args.id, args.expression, t);
        }
    }

    private static String successResponse(ParsedArgs args, List<JsonNode> results) {
        ObjectMapper mapper = JsonUtils.getMapper();
        StringBuilder body = new StringBuilder();
        for (JsonNode node : results) {
            String rendered;
            try {
                rendered = mapper.writeValueAsString(node);
            } catch (Exception e) {
                // Should not happen for jq output (it comes from the same Jackson tree
                // that just deserialized the cached JSON), but if it does, we still want
                // to surface a row to the agent — log so we can diagnose later.
                log.warn("jq result rendering failed for '{}:{}' expression='{}', falling back to toString()",
                        args.type, args.id, args.expression, e);
                rendered = node.toString();
            }
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(rendered);
            if (body.length() > OUTPUT_CAP_CHARS) {
                break;
            }
        }
        boolean truncated = body.length() > OUTPUT_CAP_CHARS;
        String capped = StringTruncator.truncate(body.toString(), OUTPUT_CAP_CHARS, OUTPUT_TRUNCATION_HINT, "\n");
        String response = successHeader(args) + "\n" + capped;
        log.debug("jq summary: type={}, id={}, expression='{}', resultCount={}, outputBytes={}, truncated={}",
                args.type.name().toLowerCase(), args.id, args.expression,
                results.size(), response.length(), truncated);
        return response;
    }

    private static String errorResponse(ParsedArgs args, Throwable t) {
        String message = t.getMessage();
        if (message == null) {
            message = t.getClass().getSimpleName();
        }
        return errorHeader(args) + "\n" + message;
    }

    private static String successHeader(ParsedArgs args) {
        return "[jq: %s:%s | expression='%s']"
                .formatted(args.type.name().toLowerCase(), args.id, args.expression);
    }

    private static String errorHeader(ParsedArgs args) {
        return "[jq: %s:%s | expression='%s' | ERROR]"
                .formatted(args.type.name().toLowerCase(), args.id, args.expression);
    }

    // ---------------- Argument parsing ----------------

    private static ParsedArgs parseArgs(String arguments) {
        if (arguments == null) {
            return ParsedArgs.error(ToolArgs.errorJson("Missing arguments"));
        }
        try {
            JsonNode node = JsonUtils.getJsonNodeFromString(arguments);
            if (node == null || !node.isObject()) {
                return ParsedArgs.error(ToolArgs.errorJson("Arguments must be a JSON object"));
            }
            var typeRes = ToolArgs.parseType(node, NAME);
            if (typeRes.isError()) {
                return ParsedArgs.error(typeRes.error());
            }
            var idRes = ToolArgs.requireString(node, "id");
            if (idRes.isError()) {
                return ParsedArgs.error(idRes.error());
            }
            var exprRes = ToolArgs.requireString(node, "expression");
            if (exprRes.isError()) {
                return ParsedArgs.error(exprRes.error());
            }
            return new ParsedArgs(typeRes.value(), idRes.value(), exprRes.value(), null);
        } catch (Exception e) {
            log.warn("Failed to parse jq tool arguments: '{}'", arguments, e);
            return ParsedArgs.error(ToolArgs.errorJson("Malformed arguments: " + e.getMessage()));
        }
    }

    private record ParsedArgs(EntityType type, String id, String expression, String error) {
        static ParsedArgs error(String err) {
            return new ParsedArgs(null, null, null, err);
        }
    }
}
