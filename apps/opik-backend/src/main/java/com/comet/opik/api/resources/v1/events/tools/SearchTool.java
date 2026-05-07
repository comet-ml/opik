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
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Per-entity regex location within a cached entity. Pairs with {@link JqTool}:
 * {@code search} locates (returns matching jq paths and truncated values),
 * {@code jq} extracts.
 *
 * <p>Args: {@code {type, id, pattern, path?}}.
 * <ul>
 *   <li>{@code pattern} — regex; matched case-insensitively against every
 *       string node reachable under {@code path}.</li>
 *   <li>{@code path} — optional jq scope expression; default {@code .}
 *       (whole entity).</li>
 * </ul>
 *
 * <p>Search semantics (see FEATURE_DESIGN_LlmJudgeAgenticTools.md §5.4):
 * <ul>
 *   <li><strong>String values only</strong> — {@code .. | strings} recurses
 *       and yields string nodes; keys, numbers, booleans, nulls are not
 *       searched.</li>
 *   <li>Paths returned in dot-bracket form
 *       (e.g. {@code spans[2].error_info.message}), directly usable as a
 *       follow-up {@link JqTool} expression — no re-quoting needed.</li>
 *   <li>Up to {@link #MAX_MATCHES} (50) match rows; each match value
 *       truncated to {@link #VALUE_TRUNCATION_LENGTH} (200) chars with the
 *       standard {@code [TRUNCATED N chars]} suffix.</li>
 *   <li>Whole-output cap {@link #OUTPUT_CAP_CHARS} (16 KB) — truncated text
 *       hints the agent to constrain via the {@code path} parameter.</li>
 * </ul>
 *
 * <p>Returns plain text (not JSON) — denser than JSON for a list-of-rows
 * structure. Cache miss returns the same hint as {@link JqTool}.
 *
 * <p><strong>No deadline is wired</strong> — same gate decision as
 * {@link JqTool} (jackson-jq ignores {@code Thread.interrupt()}).
 */
@Singleton
@Slf4j
public class SearchTool implements ToolExecutor {

    public static final String NAME = "search";

    static final int MAX_MATCHES = 50;
    static final int VALUE_TRUNCATION_LENGTH = 200;
    /** Whole-output cap, ~ 4 K tokens — matches {@link JqTool#OUTPUT_CAP_CHARS}. */
    static final int OUTPUT_CAP_CHARS = 16 * 1024;
    static final String OUTPUT_TRUNCATION_HINT = "narrow with the path parameter";

    private static final ToolSpecification SPEC = ToolSpecification.builder()
            .name(NAME)
            .description("Locate strings inside an already-cached entity (active trace, or any"
                    + " entity previously fetched via the read tool). Pass"
                    + " {\"type\": \"<type>\", \"id\": \"<id>\", \"pattern\": \"<regex>\"} — optionally"
                    + " add \"path\": \"<jq path>\" to scope the search to a sub-tree (e.g. \".spans\")."
                    + " The regex is matched case-insensitively against every string value reachable"
                    + " under the scope (keys / numbers / booleans / nulls are ignored). Returns up to"
                    + " 50 matches as <path>: <value> rows; each row's path is a jq expression you can"
                    + " feed straight into the jq tool to extract the full value.")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("type",
                            "Entity type: one of trace, span, dataset, dataset_item, project.")
                    .addStringProperty("id", "Entity id (UUID).")
                    .addStringProperty("pattern",
                            "Regex matched case-insensitively against every string value in scope.")
                    .addStringProperty("path",
                            "Optional jq scope expression to narrow the search (e.g. '.spans');"
                                    + " defaults to '.' (whole entity).")
                    .required("type", "id", "pattern")
                    .build())
            .build();

    /**
     * Locates every string value under {@code SCOPE} matching the regex
     * {@code PATTERN} (case-insensitively), returns at most 50 matches with
     * 200-char-truncated values, plus the true total. Path output format
     * is dot-bracket (e.g. {@code spans[2].input}).
     *
     * <p>{@code %1$s} is the jq scope expression (interpolated unescaped —
     * it's a jq expression, not a literal). {@code %2$s} is the regex
     * pattern, JSON-escaped via {@link ObjectMapper#writeValueAsString} so
     * the literal embeds safely as a jq string.
     */
    private static final String SEARCH_TEMPLATE = """
            . as $root
            | [path(%1$s | .. | strings | select(test(%2$s; "i")))]
            | {total: length, matches: (.[:%3$d] | map(. as $p | {
                path: ([$p[] | if type == "number" then "[\\(.)]" else ".\\(.)" end]
                       | join("") | ltrimstr(".")),
                value: ($root | getpath($p) as $v
                        | if ($v | length) > %4$d
                          then ($v[:%4$d] + "[TRUNCATED \\(($v | length) - %4$d) chars]")
                          else $v end)
              }))}
            """;

    /**
     * Restricts {@code path} to pure field/index access — no pipes, function
     * calls, parens, slices, or string-quoted keys. Matches what the tool spec
     * advertises ("jq scope expression to narrow the search, e.g. '.spans'")
     * and prevents the LLM (steerable by malicious trace content) from
     * smuggling a full jq program into a slot that's interpolated unescaped
     * into {@link #SEARCH_TEMPLATE}. Allows: {@code .}, {@code .foo},
     * {@code .foo.bar}, {@code .spans[2].input}.
     */
    private static final Pattern SAFE_PATH_PATTERN = Pattern.compile(
            "^\\.$|^\\.(?:[A-Za-z_][A-Za-z_0-9]*|\\[\\d+])(?:\\.[A-Za-z_][A-Za-z_0-9]*|\\[\\d+])*$");

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
            log.debug("search tool received invalid arguments: '{}' -> '{}'", arguments, args.error);
            return args.error;
        }
        log.debug("search tool call with valid arguments: '{}' -> '{}'", arguments, args);

        EntityRef ref = new EntityRef(args.type, args.id);
        Optional<JsonNode> cached = ctx.getCached(ref);
        if (cached.isEmpty()) {
            log.debug("search tool cache miss for id={}, ref={}", args.id, ref);
            return ToolArgs.cacheMiss(args.type, args.id);
        }

        return runSearch(cached.get(), args);
    }

    private static String runSearch(JsonNode input, ParsedArgs args) {
        String scope = (args.path == null || args.path.isBlank()) ? "." : args.path;
        String safePattern;
        try {
            safePattern = JsonUtils.getMapper().writeValueAsString(args.pattern);
        } catch (Exception e) {
            log.warn("search tool failed to JSON-escape pattern '{}'", args.pattern, e);
            return errorResponse(args, "Failed to encode pattern: " + e.getMessage());
        }

        String jqExpression = SEARCH_TEMPLATE.formatted(
                scope, safePattern, MAX_MATCHES, VALUE_TRUNCATION_LENGTH);

        JsonQuery query;
        try {
            query = JsonQuery.compile(jqExpression, Versions.JQ_1_6);
        } catch (Throwable t) {
            logEvaluationFailure("compile", args, t);
            return errorResponse(args, regexFailureMessage(t));
        }

        var results = new ArrayList<JsonNode>();
        try {
            Scope childScope = Scope.newChildScope(ROOT_SCOPE);
            query.apply(childScope, input, results::add);
        } catch (Throwable t) {
            logEvaluationFailure("apply", args, t);
            return errorResponse(args, regexFailureMessage(t));
        }

        if (results.isEmpty()) {
            return successHeader(args, 0, 0) + "\n"
                    + "No matches found. Try a broader pattern or check (type, id).";
        }

        JsonNode payload = results.getFirst();
        int total = payload.has("total") ? payload.get("total").asInt() : 0;
        JsonNode matches = payload.path("matches");
        int shown = matches.isArray() ? matches.size() : 0;

        if (total == 0) {
            return successHeader(args, 0, 0) + "\n"
                    + "No matches found. Try a broader pattern or check (type, id).";
        }

        StringBuilder body = new StringBuilder();
        body.append(successHeader(args, total, shown));
        for (JsonNode match : matches) {
            body.append('\n');
            String path = match.path("path").asText();
            JsonNode value = match.path("value");
            body.append(path).append(": ").append(renderValue(value));
        }
        boolean truncated = body.length() > OUTPUT_CAP_CHARS;
        String response = StringTruncator.truncate(body.toString(), OUTPUT_CAP_CHARS, OUTPUT_TRUNCATION_HINT, "\n");
        log.debug(
                "search summary: type={}, id={}, pattern='{}', path='{}', totalMatches={}, returned={}, outputBytes={}, truncated={}",
                args.type.name().toLowerCase(), args.id, args.pattern, args.path,
                total, shown, response.length(), truncated);
        return response;
    }

    private static String renderValue(JsonNode value) {
        try {
            return JsonUtils.getMapper().writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private static void logEvaluationFailure(String stage, ParsedArgs args, Throwable t) {
        if (t instanceof JsonQueryException) {
            log.debug("search {} failed for {}:{} pattern='{}' path='{}': {}",
                    stage, args.type, args.id, args.pattern, args.path, t.getMessage());
        } else {
            log.warn("search {} crashed for {}:{} pattern='{}' path='{}'",
                    stage, args.type, args.id, args.pattern, args.path, t);
        }
    }

    private static String regexFailureMessage(Throwable t) {
        String message = t.getMessage();
        return "Regex failure: " + (message != null ? message : t.getClass().getSimpleName());
    }

    private static String successHeader(ParsedArgs args, int total, int shown) {
        StringBuilder header = headerPrefix(args);
        if (total > shown) {
            header.append(" | ").append(total).append(" matches (showing ").append(shown).append(')');
        } else {
            header.append(" | ").append(total).append(" matches");
        }
        header.append(']');
        return header.toString();
    }

    private static String errorResponse(ParsedArgs args, String message) {
        return errorHeader(args) + "\n" + message;
    }

    private static String errorHeader(ParsedArgs args) {
        return headerPrefix(args).append(" | ERROR]").toString();
    }

    private static StringBuilder headerPrefix(ParsedArgs args) {
        StringBuilder header = new StringBuilder("[search: ");
        header.append(args.type.name().toLowerCase()).append(':').append(args.id);
        header.append(" | pattern='").append(args.pattern).append('\'');
        if (args.path != null && !args.path.isBlank()) {
            header.append(" | path='").append(args.path).append('\'');
        }
        return header;
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
            var patternRes = ToolArgs.requireString(node, "pattern");
            if (patternRes.isError()) {
                return ParsedArgs.error(patternRes.error());
            }
            String path = ToolArgs.textOrNull(node.get("path"));
            if (path != null && !path.isBlank() && !SAFE_PATH_PATTERN.matcher(path).matches()) {
                return ParsedArgs.error(ToolArgs.errorJson(
                        "path must be a simple jq scope expression (e.g. '.', '.spans', '.spans[2].input') —"
                                + " only dotted field names and bracketed integer indices are allowed."));
            }
            return new ParsedArgs(typeRes.value(), idRes.value(), patternRes.value(), path, null);
        } catch (Exception e) {
            log.warn("Failed to parse search tool arguments: '{}'", arguments, e);
            return ParsedArgs.error(ToolArgs.errorJson("Malformed arguments: " + e.getMessage()));
        }
    }

    private record ParsedArgs(EntityType type, String id, String pattern, String path, String error) {
        static ParsedArgs error(String err) {
            return new ParsedArgs(null, null, null, null, err);
        }
    }
}
