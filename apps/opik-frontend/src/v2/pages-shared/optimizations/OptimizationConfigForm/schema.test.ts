import { describe, it, expect } from "vitest";
import {
  hasPythonSyntaxError,
  CodeMetricParamsSchema,
  OptimizationConfigSchema,
  OptimizationConfigFormType,
} from "./schema";
import { METRIC_TYPE, OPTIMIZER_TYPE } from "@/types/optimizations";
import { LLM_MESSAGE_ROLE } from "@/types/llm";

const VALID_CODE_METRIC = `
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class LabelMatch(BaseMetric):
    def __init__(self, name: str = "label_match"):
        super().__init__(name=name)

    def score(self, output: str, **kwargs) -> ScoreResult:
        label = str(kwargs.get("label", "")).strip().lower()
        return ScoreResult(name=self.name, value=1.0 if label else 0.0)
`;

// Missing the colon after the class definition — a plain syntax error, not a
// semantic/runtime one, so the Lezer-based check must flag it.
const SYNTAX_ERROR_CODE_METRIC = `
from opik.evaluation.metrics import BaseMetric


class BrokenMetric(BaseMetric)
    def __init__(self, name: str = "broken"):
        super().__init__(name=name)
`;

describe("hasPythonSyntaxError", () => {
  it("returns false for valid Python", () => {
    expect(hasPythonSyntaxError(VALID_CODE_METRIC)).toBe(false);
  });

  it("returns true for a missing colon", () => {
    expect(hasPythonSyntaxError(SYNTAX_ERROR_CODE_METRIC)).toBe(true);
  });

  it("returns false for valid code that reads a required kwarg (no false positive)", () => {
    // Regression guard: the syntax check must never flag a semantically
    // dynamic (but syntactically valid) access like kwargs["x"] — only real
    // syntax errors are in scope (OPIK-7172).
    const code = `
from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


class StrictKwargMetric(BaseMetric):
    def score(self, output, **kwargs):
        expected = kwargs["expected_value"]
        return ScoreResult(name=self.name, value=1.0 if output == expected else 0.0)
`;
    expect(hasPythonSyntaxError(code)).toBe(false);
  });

  it("flags empty code as a parse error (callers guard on `code &&` instead)", () => {
    // The Lezer parser reports an empty program as an error node, so this
    // function alone would flag "" too. `CodeMetricParamsSchema` below never
    // hits that path in practice: it only calls this once `.min(1)` has
    // already confirmed `code` is non-empty (`params.code && ...`).
    expect(hasPythonSyntaxError("")).toBe(true);
  });
});

describe("CodeMetricParamsSchema", () => {
  it("accepts valid Python code", () => {
    const result = CodeMetricParamsSchema.safeParse({
      code: VALID_CODE_METRIC,
    });
    expect(result.success).toBe(true);
  });

  it("rejects code with a syntax error and anchors the issue to the 'code' field", () => {
    const result = CodeMetricParamsSchema.safeParse({
      code: SYNTAX_ERROR_CODE_METRIC,
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      const codeIssue = result.error.issues.find(
        (issue) => issue.path.join(".") === "code",
      );
      expect(codeIssue).toBeDefined();
      expect(codeIssue?.message).toMatch(/syntax error/i);
    }
  });

  it("accepts an optional rename-capable arguments map", () => {
    const result = CodeMetricParamsSchema.safeParse({
      code: VALID_CODE_METRIC,
      arguments: { reference: "expected_answer" },
    });
    expect(result.success).toBe(true);
  });
});

describe("OptimizationConfigSchema — code metric syntax-error submission block", () => {
  const baseConfig: Omit<
    OptimizationConfigFormType,
    "metricType" | "metricParams"
  > = {
    name: "",
    datasetId: "dataset-1",
    optimizerType: OPTIMIZER_TYPE.GEPA,
    optimizerParams: {},
    messages: [
      {
        id: "1",
        role: LLM_MESSAGE_ROLE.user,
        content: "Classify: {{text}}",
      },
    ],
    modelName: "anthropic/claude-haiku",
    modelConfig: {},
  };

  it("passes end-to-end validation for a valid code metric", () => {
    const result = OptimizationConfigSchema.safeParse({
      ...baseConfig,
      metricType: METRIC_TYPE.CODE,
      metricParams: { code: VALID_CODE_METRIC },
    });
    expect(result.success).toBe(true);
  });

  it("blocks submission end-to-end when the code metric has a syntax error", () => {
    // This is the exact resolver (`zodResolver(OptimizationConfigSchema)`)
    // NewRunSidebar wires up, so a failing parse here is what stops RHF's
    // `handleSubmit` from ever invoking the submit callback in the real form.
    const result = OptimizationConfigSchema.safeParse({
      ...baseConfig,
      metricType: METRIC_TYPE.CODE,
      metricParams: { code: SYNTAX_ERROR_CODE_METRIC },
    });
    expect(result.success).toBe(false);
  });
});
