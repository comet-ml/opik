import { describe, expect, it } from "vitest";

import {
  TRACE_CHIP_ORDER,
  buildTraceChipDefinitions,
} from "./traceChipDefinitions";
import { LOGS_SOURCE } from "@/types/traces";
import { ChipOptionsConfig } from "@/shared/filter-chips/types";

const options = { items: [], isLoading: false };

const build = (
  overrides: Partial<Parameters<typeof buildTraceChipDefinitions>[0]> = {},
) =>
  buildTraceChipDefinitions({
    projectId: "p1",
    traceScoreOptions: options,
    spanScoreOptions: options,
    isGuardrailsEnabled: false,
    logsSource: LOGS_SOURCE.sdk,
    ...overrides,
  });

/**
 * The args a chip's option hook will be called with, for the chips that source options remotely.
 * ChipDefinition is a union and only some members carry `value` / `key`, so this reads them off a
 * widened shape rather than narrowing per chip kind.
 */
const optionArgs = (
  definitions: ReturnType<typeof buildTraceChipDefinitions>,
  chipId: string,
) => {
  const chip = definitions.find((d) => d.id === chipId) as
    | {
        value?: { options?: ChipOptionsConfig };
        key?: { options?: ChipOptionsConfig };
      }
    | undefined;
  const config = chip?.value?.options ?? chip?.key?.options;
  return config && "args" in config
    ? (config.args as Record<string, unknown>)
    : undefined;
};

describe("buildTraceChipDefinitions", () => {
  it("returns the chips in the declared order", () => {
    const ids = build().map((d) => d.id);
    expect(ids).toEqual(TRACE_CHIP_ORDER.filter((id) => id !== "guardrails"));
  });

  it("adds the guardrails chip only when the feature is on, in its declared slot", () => {
    expect(build().map((d) => d.id)).not.toContain("guardrails");

    const ids = build({ isGuardrailsEnabled: true }).map((d) => d.id);
    expect(ids).toContain("guardrails");
    expect(ids).toEqual(TRACE_CHIP_ORDER);
  });

  // The source decides which traces the tag / error-type / metadata-path autocompletes are computed
  // over. Getting it wrong shows a surface options its own table would never match.
  it.each([
    ["tags", "tags"],
    ["error_type", "error_type"],
    ["metadata", "metadata"],
    ["custom", "custom"],
  ])("forwards the logs source to the %s options", (_name, chipId) => {
    expect(optionArgs(build(), chipId)).toMatchObject({
      logsSource: LOGS_SOURCE.sdk,
    });

    expect(
      optionArgs(build({ logsSource: LOGS_SOURCE.evaluator }), chipId),
    ).toMatchObject({ logsSource: LOGS_SOURCE.evaluator });

    expect(optionArgs(build({ logsSource: undefined }), chipId)).toMatchObject({
      logsSource: undefined,
    });
  });

  it("keeps trace and span feedback scores as separate chips", () => {
    const definitions = build();
    const trace = definitions.find((d) => d.id === "feedback_scores");
    const span = definitions.find((d) => d.id === "span_feedback_scores");

    expect(trace?.label).toBe("Trace feedback scores");
    expect(span?.label).toBe("Span feedback scores");
    expect(trace?.field).not.toBe(span?.field);
  });
});
