import { describe, expect, it } from "vitest";

import {
  getDecisionModelReservedVariables,
  hasSingleUserMessage,
  isDecisionModelTemplate,
  toDecisionModelSchema,
} from "./decisionModelRule";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { LLM_MESSAGE_ROLE, LLM_SCHEMA_TYPE, LLMJudgeSchema } from "@/types/llm";
import {
  LLM_PROMPT_CUSTOM_TRACE_TEMPLATE,
  LLM_PROMPT_TEMPLATES,
} from "@/constants/llm";
import { isDecisionModel } from "@/lib/modelCapabilities";

const score = (name: string, type: LLM_SCHEMA_TYPE): LLMJudgeSchema => ({
  name,
  type,
  description: `Is it ${name}?`,
  unsaved: false,
});

describe("isDecisionModel", () => {
  it("matches the Jev models only", () => {
    expect(isDecisionModel("~typesafe/jev-latest")).toBe(true);
    expect(isDecisionModel("typesafe/jev-1.13")).toBe(true);
    expect(isDecisionModel("typesafe/jev-latest")).toBe(false);
    expect(isDecisionModel("gpt-4o")).toBe(false);
    expect(isDecisionModel("")).toBe(false);
    expect(isDecisionModel(undefined)).toBe(false);
  });
});

describe("toDecisionModelSchema", () => {
  it("keeps Boolean scores and reports the removed ones", () => {
    const result = toDecisionModelSchema(
      [
        score("relevant", LLM_SCHEMA_TYPE.BOOLEAN),
        score("quality", LLM_SCHEMA_TYPE.INTEGER),
        score("tone", LLM_SCHEMA_TYPE.DOUBLE),
        score("correct", LLM_SCHEMA_TYPE.BOOLEAN),
      ],
      EVALUATORS_RULE_SCOPE.trace,
    );

    expect(result.schema.map((s) => s.name)).toEqual(["relevant", "correct"]);
    expect(result.removedScoreNames).toEqual(["quality", "tone"]);
  });

  it("falls back to the custom-template score when no Boolean score is left", () => {
    const result = toDecisionModelSchema(
      [score("quality", LLM_SCHEMA_TYPE.INTEGER)],
      EVALUATORS_RULE_SCOPE.trace,
    );

    expect(result.schema).toEqual(LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.schema);
    expect(result.schema).not.toBe(LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.schema);
    expect(result.removedScoreNames).toEqual(["quality"]);
  });

  it("changes nothing when every score is Boolean", () => {
    const schema = [score("relevant", LLM_SCHEMA_TYPE.BOOLEAN)];

    const result = toDecisionModelSchema(schema, EVALUATORS_RULE_SCOPE.span);

    expect(result.schema).toEqual(schema);
    expect(result.removedScoreNames).toEqual([]);
  });
});

describe("isDecisionModelTemplate", () => {
  it("keeps only templates with Boolean scores and a single user message", () => {
    expect(
      LLM_PROMPT_TEMPLATES[EVALUATORS_RULE_SCOPE.trace]
        .filter(isDecisionModelTemplate)
        .map((t) => t.value),
    ).toEqual(["custom", "structure_compliance", "meaning_match"]);
  });
});

describe("hasSingleUserMessage", () => {
  const message = (role: LLM_MESSAGE_ROLE) => ({
    id: role,
    role,
    content: "text",
  });

  it("requires exactly one message, from the user", () => {
    expect(hasSingleUserMessage([message(LLM_MESSAGE_ROLE.user)])).toBe(true);
    expect(hasSingleUserMessage([])).toBe(false);
    expect(hasSingleUserMessage([message(LLM_MESSAGE_ROLE.system)])).toBe(
      false,
    );
    expect(
      hasSingleUserMessage([
        message(LLM_MESSAGE_ROLE.system),
        message(LLM_MESSAGE_ROLE.user),
      ]),
    ).toBe(false);
  });
});

describe("getDecisionModelReservedVariables", () => {
  it("leaves out the structure variable of each scope", () => {
    expect(
      getDecisionModelReservedVariables(EVALUATORS_RULE_SCOPE.trace),
    ).toEqual({ spans: "spans" });
    expect(
      getDecisionModelReservedVariables(EVALUATORS_RULE_SCOPE.span),
    ).toEqual({});
  });
});
