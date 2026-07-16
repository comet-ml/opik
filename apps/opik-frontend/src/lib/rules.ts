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
 * Attempts to extract metric name from Python code by parsing the __init__ default parameter.
 * Looks for patterns like: def __init__(self, name: str = "my_custom_metric")
 * Returns the extracted name or null if no name can be extracted.
 */
export const extractMetricNameFromPythonCode = (
  code: string,
): string | null => {
  if (!code) return null;

  // Strip comments/docstrings first so none of the patterns below match text
  // that never executes (e.g. `super().__init__(name="fake")` in a docstring).
  const source = stripPythonCommentsAndDocstrings(code);

  // Mirror the backend's static name extraction
  // (opik-python-backend process_worker._metric_name_ast) so a name derived here
  // — e.g. for the Optimization Studio create-time `objective_name` — matches the
  // name the metric actually scores under. Otherwise the UI keys feedback scores
  // by the wrong name and shows "-". Tried in the same precedence order:

  // 1. Name passed to the base constructor: super().__init__(name="metric_name").
  //    The dominant idiom, and the one the backend AST prefers first.
  const superCtor = source.match(
    /super\(\)\s*\.\s*__init__\s*\([^)]*\bname\s*=\s*["']([^"']+)["']/,
  );
  if (superCtor) return superCtor[1];

  // 2. Default of __init__'s `name` param: def __init__(self, name="metric_name").
  const initDefault = source.match(
    /def\s+__init__\s*\([^)]*\bname(?:\s*:\s*[^=,)]+)?\s*=\s*["']([^"']+)["']/,
  );
  if (initDefault) return initDefault[1];

  // 3. Class-level attribute: `name = "metric_name"` at the class-body
  //    indentation level. The backend only reads a direct assignment in the
  //    class body (ast.Assign in cls.body), so we scope the match to that level
  //    — this excludes method-local assignments (e.g. `name = "tmp"` inside
  //    `def score(...)`, which are more deeply indented) and `self.name = ...`.
  const classAttr = extractClassBodyName(source);
  if (classAttr) return classAttr;

  return null;
};

const leadingWhitespace = (line: string): string =>
  line.match(/^[ \t]*/)?.[0] ?? "";

/**
 * Finds a class-body-level `name = "..."` assignment (not inside a nested block
 * such as a method body). Scans every class declaration in source — so a helper
 * class declared before the metric class does not cause a miss — and returns the
 * first class-body assignment found.
 */
const extractClassBodyName = (source: string): string | null => {
  const classRegex = /^([ \t]*)class\s+\w+[^\n]*:/gm;
  let classMatch: RegExpExecArray | null;
  while ((classMatch = classRegex.exec(source)) !== null) {
    const classIndent = classMatch[1];
    const bodyLines = source
      .slice(classMatch.index + classMatch[0].length)
      .split("\n");

    // The class-body indentation is the indent of the first non-blank line that
    // is more indented than the `class` line itself.
    let bodyIndent: string | null = null;
    for (const line of bodyLines) {
      if (!line.trim()) continue;
      const indent = leadingWhitespace(line);
      if (indent.length > classIndent.length) bodyIndent = indent;
      break;
    }
    if (bodyIndent === null) continue;

    for (const line of bodyLines) {
      // Stop at the end of this class body (dedent to/below the class header).
      if (line.trim() && leadingWhitespace(line).length <= classIndent.length) {
        break;
      }
      if (leadingWhitespace(line) !== bodyIndent) continue;
      const match = line.match(/^[ \t]*name\s*=\s*["']([^"']+)["']/);
      if (match) return match[1];
    }
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
