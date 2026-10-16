import { describe, expect, it } from "vitest";

import { convertToStandardSignature } from "./codeSignature";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";

describe("convertToStandardSignature", () => {
  it("replaces mapped parameters with standard ones and reads the mapped paths in the body", () => {
    const code = [
      "class M(base_metric.BaseMetric):",
      "    def score(",
      "        self, question, input, tag, spans, threshold: float = 0.5",
      "    ) -> score_result.ScoreResult:",
      "",
      "        return score_result.ScoreResult(value=1, name=self.name)",
    ].join("\n");

    expect(
      convertToStandardSignature(code, EVALUATORS_RULE_SCOPE.trace, {
        question: "input.messages[0].content",
        input: "metadata.user",
        tag: "production",
        spans: "spans",
      }),
    ).toBe(
      [
        "import json",
        "class M(base_metric.BaseMetric):",
        "    def score(self, input, metadata=None, spans=None, threshold: float = 0.5) -> score_result.ScoreResult:",
        '        question = json.loads(input)["messages"][0]["content"]',
        '        tag = "production"',
        '        input = json.loads(metadata)["user"]',
        "",
        "        return score_result.ScoreResult(value=1, name=self.name)",
      ].join("\n"),
    );
  });
});
