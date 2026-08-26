import { describe, expect, it } from "vitest";

import { resolveTraceEvaluatorVariableDefault } from "./llm";
import {
  RESERVED_SPAN_LLM_JUDGE_VARIABLES,
  RESERVED_TRACE_EVALUATOR_VARIABLES,
  RESERVED_TRACE_LLM_JUDGE_VARIABLES,
} from "@/constants/llm";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";

/**
 * Covers the sentinel auto-fill that decides whether a reserved variable name
 * (`spans` / `trace` / `span`) is pre-mapped to its sentinel or left blank for the
 * user to point at a custom path. This used to be gated by the agentic-tools
 * feature toggle; with the toggle removed the auto-fill is unconditional, so these
 * assertions are what pins the behaviour down.
 */
describe("resolveTraceEvaluatorVariableDefault", () => {
  it("auto-fills the spans sentinel on trace scope", () => {
    expect(
      resolveTraceEvaluatorVariableDefault(
        "spans",
        undefined,
        EVALUATORS_RULE_SCOPE.trace,
        RESERVED_TRACE_LLM_JUDGE_VARIABLES,
      ),
    ).toBe("spans");
  });

  it("auto-fills the trace sentinel on trace scope for LLM judges", () => {
    expect(
      resolveTraceEvaluatorVariableDefault(
        "trace",
        undefined,
        EVALUATORS_RULE_SCOPE.trace,
        RESERVED_TRACE_LLM_JUDGE_VARIABLES,
      ),
    ).toBe("trace");
  });

  it("auto-fills the span sentinel on span scope", () => {
    expect(
      resolveTraceEvaluatorVariableDefault(
        "span",
        undefined,
        EVALUATORS_RULE_SCOPE.span,
        RESERVED_SPAN_LLM_JUDGE_VARIABLES,
      ),
    ).toBe("span");
  });

  it("does not auto-fill trace for Python metrics, whose backend only handles spans", () => {
    // The default reserved set deliberately omits `trace`: auto-mapping it would
    // inject a value the Python scorer ignores.
    expect(
      resolveTraceEvaluatorVariableDefault(
        "trace",
        undefined,
        EVALUATORS_RULE_SCOPE.trace,
        RESERVED_TRACE_EVALUATOR_VARIABLES,
      ),
    ).toBe("");
  });

  it("leaves non-reserved names blank", () => {
    expect(
      resolveTraceEvaluatorVariableDefault(
        "my_custom_var",
        undefined,
        EVALUATORS_RULE_SCOPE.trace,
        RESERVED_TRACE_LLM_JUDGE_VARIABLES,
      ),
    ).toBe("");
  });

  it("does not auto-fill on thread scope, which uses {{context}} instead", () => {
    expect(
      resolveTraceEvaluatorVariableDefault(
        "spans",
        undefined,
        EVALUATORS_RULE_SCOPE.thread,
        RESERVED_TRACE_LLM_JUDGE_VARIABLES,
      ),
    ).toBe("");
  });

  it("preserves a mapping the user already set", () => {
    expect(
      resolveTraceEvaluatorVariableDefault(
        "spans",
        "output.custom.path",
        EVALUATORS_RULE_SCOPE.trace,
        RESERVED_TRACE_LLM_JUDGE_VARIABLES,
      ),
    ).toBe("output.custom.path");
  });

  it("preserves a deliberate empty-string mapping rather than re-applying the sentinel", () => {
    // Treating "" as "not set" would silently clobber an API caller's explicit
    // `spans: ""` on every prompt re-parse.
    //
    // Note this state is reachable only via the API: the rule dialog's own schema
    // rejects an empty mapping value (`.min(1)` plus the JSONPath/sentinel regex in
    // AddEditRuleDialog/schema.ts), so the editor will not produce or re-submit one.
    // The resolver still must not rewrite it — preserving a value it cannot author is
    // what keeps an API-created rule's prompt re-parse non-destructive.
    expect(
      resolveTraceEvaluatorVariableDefault(
        "spans",
        "",
        EVALUATORS_RULE_SCOPE.trace,
        RESERVED_TRACE_LLM_JUDGE_VARIABLES,
      ),
    ).toBe("");
  });
});
