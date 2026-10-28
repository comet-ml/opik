import { describe, expect, it } from "vitest";

import { GuardrailTypes } from "@/types/guardrails";
import { buildGuardrailCode, guardrailsDefaultState } from "./guardrailsConfig";

const enabledByDefault = [GuardrailTypes.TOPIC, GuardrailTypes.PII];

describe("buildGuardrailCode", () => {
  it("leaves Topic out while no topic is entered, as the SDK rejects an empty Topic", () => {
    expect(
      buildGuardrailCode(guardrailsDefaultState, enabledByDefault),
    ).toEqual({
      importNames: ["PII"],
      codeList: ['PII(entities=["CREDIT_CARD","PHONE_NUMBER"])'],
    });
  });

  it("includes Topic once a topic is entered, trimmed", () => {
    const state = {
      ...guardrailsDefaultState,
      [GuardrailTypes.TOPIC]: {
        ...guardrailsDefaultState[GuardrailTypes.TOPIC],
        entities: [" finance ", ""],
      },
    };

    expect(buildGuardrailCode(state, enabledByDefault).codeList).toEqual([
      'Topic(restricted_topics=["finance"])',
      'PII(entities=["CREDIT_CARD","PHONE_NUMBER"])',
    ]);
  });

  it("leaves PII out when every category is cleared", () => {
    const state = {
      ...guardrailsDefaultState,
      [GuardrailTypes.PII]: {
        ...guardrailsDefaultState[GuardrailTypes.PII],
        entities: [],
      },
    };

    expect(
      buildGuardrailCode(state, [
        GuardrailTypes.PII,
        GuardrailTypes.PROMPT_INJECTION,
      ]),
    ).toEqual({
      importNames: ["PromptInjection"],
      codeList: ["PromptInjection()"],
    });
  });
});
