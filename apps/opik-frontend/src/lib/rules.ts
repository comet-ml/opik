import {
  EvaluatorsRule,
  EVALUATORS_RULE_TYPE,
  LLMJudgeDetails,
  PythonCodeDetails,
} from "@/types/automations";
import { LLMJudgeSchema } from "@/types/llm";

export const isPythonCodeRule = (rule: EvaluatorsRule): boolean => {
  return (
    rule.type === EVALUATORS_RULE_TYPE.python_code ||
    rule.type === EVALUATORS_RULE_TYPE.thread_python_code ||
    rule.type === EVALUATORS_RULE_TYPE.span_python_code
  );
};

/**
 * Removes Python comments (`# ...`) and triple-quoted strings (docstrings) from
 * source, while preserving ordinary single-/double-quoted string literals (they
 * may hold the metric name we want to extract). This keeps the regex-based
 * extractor from matching a `super().__init__(name="fake")` or `name = "fake"`
 * that only appears inside a comment or docstring.
 */
const stripPythonCommentsAndDocstrings = (code: string): string => {
  let result = "";
  let i = 0;
  const n = code.length;
  while (i < n) {
    const three = code.slice(i, i + 3);
    // Triple-quoted string (docstring) — drop it entirely.
    if (three === '"""' || three === "'''") {
      const end = code.indexOf(three, i + 3);
      i = end === -1 ? n : end + 3;
      continue;
    }
    const ch = code[i];
    // Line comment — drop to end of line (keep the newline for line structure).
    if (ch === "#") {
      const nl = code.indexOf("\n", i);
      i = nl === -1 ? n : nl;
      continue;
    }
    // Ordinary string literal — copy verbatim (may contain the metric name).
    if (ch === '"' || ch === "'") {
      result += ch;
      i += 1;
      while (i < n) {
        const c = code[i];
        if (c === "\\") {
          result += c + (code[i + 1] ?? "");
          i += 2;
          continue;
        }
        result += c;
        i += 1;
        if (c === ch) break;
      }
      continue;
    }
    result += ch;
    i += 1;
  }
  return result;
};

/**
 * Attempts to extract the metric name from Python code, mirroring the backend's
 * static extraction (opik-python-backend process_worker: `_find_basemetric_classdef`
 * + `_metric_name_ast`) so a name derived here — e.g. the Optimization Studio
 * create-time `objective_name` — matches the name the metric actually scores
 * under. A mismatch is worse than `null`: it makes `getFeedbackScore(...,
 * objective_name)` miss AND keeps `expectedMetricNames` polling for a name that
 * never arrives (until MAX_REFETCH_TIME). So we resolve the SAME class the
 * backend instantiates and read the name from ONLY that class; if we cannot
 * identify the BaseMetric subclass we return null and defer to the backend.
 * Returns the extracted name or null if no name can be extracted.
 */
export const extractMetricNameFromPythonCode = (
  code: string,
): string | null => {
  if (!code) return null;

  // Strip comments/docstrings first so none of the patterns below match text
  // that never executes (e.g. `super().__init__(name="fake")` in a docstring).
  const source = stripPythonCommentsAndDocstrings(code);

  // Resolve the metric class the way the backend does, then read the name from
  // that class body only — never from a helper or sibling class.
  const metricClass = findMetricClassBody(source);
  if (metricClass === null) return null;

  return extractNameFromClassBody(metricClass);
};

const leadingWhitespace = (line: string): string =>
  line.match(/^[ \t]*/)?.[0] ?? "";

/**
 * Local names that refer to `BaseMetric`: the literal plus any
 * `from ... import BaseMetric as X` alias. Mirrors `_basemetric_aliases`.
 */
const collectBaseMetricAliases = (source: string): Set<string> => {
  const aliases = new Set<string>(["BaseMetric"]);
  const importRegex = /from\s+[\w.]+\s+import\s+([^\n]+)/g;
  let match: RegExpExecArray | null;
  while ((match = importRegex.exec(source)) !== null) {
    const aliasRegex = /\bBaseMetric\s+as\s+(\w+)/g;
    let alias: RegExpExecArray | null;
    while ((alias = aliasRegex.exec(match[1])) !== null) aliases.add(alias[1]);
  }
  return aliases;
};

interface MetricClassBody {
  indent: string;
  body: string;
}

/**
 * The `BaseMetric` subclass the backend would instantiate: among classes whose
 * declared bases include `BaseMetric` (or an alias), the alphabetically-first by
 * name — mirroring `_find_basemetric_classdef` / `get_metric_class` (which pick
 * `min` by name / the name-sorted `inspect.getmembers`). Returns that class's
 * body text (without the header), or null when no such class is found.
 */
const findMetricClassBody = (source: string): MetricClassBody | null => {
  const aliases = collectBaseMetricAliases(source);
  const classRegex = /^([ \t]*)class\s+(\w+)\s*(?:\(([^)]*)\))?\s*:/gm;
  const candidates: { name: string; indent: string; bodyStart: number }[] = [];
  let match: RegExpExecArray | null;
  while ((match = classRegex.exec(source)) !== null) {
    const bases = (match[3] ?? "")
      .split(",")
      .map((base) => base.trim().split(".").pop()?.replace(/\[.*$/, "").trim())
      .filter((base): base is string => Boolean(base));
    if (bases.some((base) => aliases.has(base))) {
      candidates.push({
        name: match[2],
        indent: match[1],
        bodyStart: match.index + match[0].length,
      });
    }
  }
  if (candidates.length === 0) return null;

  // Alphabetically-first by class name (Python `min` on names, code-point order).
  candidates.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  const chosen = candidates[0];

  // Body = the lines after the header until the indentation returns to (or below)
  // the class header's own indent.
  const bodyLines: string[] = [];
  for (const line of source.slice(chosen.bodyStart).split("\n")) {
    if (line.trim() && leadingWhitespace(line).length <= chosen.indent.length) {
      break;
    }
    bodyLines.push(line);
  }
  return { indent: chosen.indent, body: bodyLines.join("\n") };
};

/**
 * Reads the metric name from a single class body, in the backend's precedence
 * order (`_metric_name_ast`): base-constructor `super().__init__(name=...)`,
 * then the `__init__` `name` param default, then a class-body-level
 * `name = "..."` assignment (scoped to the body indentation so method-local
 * `name = ...` and `self.name = ...` are excluded).
 */
const extractNameFromClassBody = (cls: MetricClassBody): string | null => {
  const superCtor = cls.body.match(
    /super\(\)\s*\.\s*__init__\s*\([^)]*\bname\s*=\s*["']([^"']+)["']/,
  );
  if (superCtor) return superCtor[1];

  const initDefault = cls.body.match(
    /def\s+__init__\s*\([^)]*\bname(?:\s*:\s*[^=,)]+)?\s*=\s*["']([^"']+)["']/,
  );
  if (initDefault) return initDefault[1];

  // Class-body indentation: the indent of the first non-blank body line.
  const bodyLines = cls.body.split("\n");
  let bodyIndent: string | null = null;
  for (const line of bodyLines) {
    if (!line.trim()) continue;
    bodyIndent = leadingWhitespace(line);
    break;
  }
  if (bodyIndent === null) return null;

  for (const line of bodyLines) {
    if (leadingWhitespace(line) !== bodyIndent) continue;
    const match = line.match(/^[ \t]*name\s*=\s*["']([^"']+)["']/);
    if (match) return match[1];
  }
  return null;
};

export const isLLMJudgeRule = (rule: EvaluatorsRule): boolean => {
  return (
    rule.type === EVALUATORS_RULE_TYPE.llm_judge ||
    rule.type === EVALUATORS_RULE_TYPE.thread_llm_judge ||
    rule.type === EVALUATORS_RULE_TYPE.span_llm_judge
  );
};

export const getScoreNamesFromRule = (rule: EvaluatorsRule): string[] => {
  if (isLLMJudgeRule(rule)) {
    const llmRule = rule as EvaluatorsRule & LLMJudgeDetails;
    return llmRule.code.schema?.map((s: LLMJudgeSchema) => s.name) || [];
  }
  if (isPythonCodeRule(rule)) {
    const pythonRule = rule as EvaluatorsRule & PythonCodeDetails;
    // Attempt to extract metric name from Python code by parsing __init__ default parameter
    // This works for the common pattern: def __init__(self, name: str = "metric_name")
    // Falls back to empty array if name cannot be extracted (dynamic names, etc.)
    const metricName = extractMetricNameFromPythonCode(
      pythonRule.code.metric || "",
    );
    return metricName ? [metricName] : [];
  }
  return [];
};
