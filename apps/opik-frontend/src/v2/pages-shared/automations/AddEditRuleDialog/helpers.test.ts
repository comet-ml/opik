import { describe, expect, it } from "vitest";

import { reservedPythonMetricVariablesForScope } from "./helpers";
import { PythonCodeDetailsSpanFormSchema } from "./schema";
import {
  RESERVED_SPAN_EVALUATOR_VARIABLES,
  RESERVED_TRACE_EVALUATOR_VARIABLES,
} from "@/constants/llm";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";

describe("reservedPythonMetricVariablesForScope", () => {
  it("reserves spans on trace scope, where the scorer injects it", () => {
    expect(
      reservedPythonMetricVariablesForScope(EVALUATORS_RULE_SCOPE.trace),
    ).toEqual(RESERVED_TRACE_EVALUATOR_VARIABLES);
  });

  it("reserves nothing on span scope, so no sentinel is auto-filled or hidden", () => {
    expect(
      reservedPythonMetricVariablesForScope(EVALUATORS_RULE_SCOPE.span),
    ).toEqual(RESERVED_SPAN_EVALUATOR_VARIABLES);
  });
});

/**
 * The other half of the span-scope pairing above: the sentinel the resolver must not
 * auto-fill is one this schema genuinely rejects, so a regression in either file alone
 * still fails a test here.
 */
describe("PythonCodeDetailsSpanFormSchema", () => {
  const metric = "def score(input, spans): return []";

  it("rejects the bare spans sentinel that trace scope allows", () => {
    expect(
      PythonCodeDetailsSpanFormSchema.safeParse({
        metric,
        arguments: { input: "input", spans: "spans" },
      }).success,
    ).toBe(false);
  });

  it("accepts the arguments the span resolver actually produces", () => {
    // `spans` left blank by the resolver is still required to be pointed at a real path
    // before submit; the user maps it explicitly, as with any non-reserved parameter.
    expect(
      PythonCodeDetailsSpanFormSchema.safeParse({
        metric,
        arguments: { input: "input", spans: "metadata.spans" },
      }).success,
    ).toBe(true);
  });
});
