import { describe, expect, it } from "vitest";

import { GuardrailPolicyRule, GuardrailTypes } from "@/types/guardrails";
import {
  DEFAULT_RULES_STATE,
  MAX_TOPIC_LENGTH,
  RulesState,
  toRulesPayload,
  toRulesState,
  validateRules,
} from "./rulesState";

const withRules = (patch: Partial<RulesState>): RulesState => ({
  ...DEFAULT_RULES_STATE,
  ...patch,
});

const withTopics = (restrictedTopics: string[], allowedTopics: string[]) =>
  withRules({
    [GuardrailTypes.TOPIC]: { enabled: true, restrictedTopics, allowedTopics },
  });

describe("toRulesState", () => {
  it("switches on the stored rules and keeps the others at their defaults", () => {
    const state = toRulesState([
      { type: GuardrailTypes.TOPIC, config: { allowed_topics: ["billing"] } },
    ]);

    expect(state[GuardrailTypes.TOPIC]).toEqual({
      enabled: true,
      restrictedTopics: [],
      allowedTopics: ["billing"],
    });
    expect(state[GuardrailTypes.PII]).toEqual(
      DEFAULT_RULES_STATE[GuardrailTypes.PII],
    );
    expect(state[GuardrailTypes.PROMPT_INJECTION].enabled).toBe(false);
  });
});

describe("toRulesPayload", () => {
  it("sends only the enabled rules and the lists in use, in the canonical order, with topics trimmed", () => {
    const payload = toRulesPayload({
      ...withTopics([" politics ", "", "weather"], [" "]),
      [GuardrailTypes.PROMPT_INJECTION]: { enabled: true },
    });

    expect(payload).toEqual([
      {
        type: GuardrailTypes.TOPIC,
        config: { restricted_topics: ["politics", "weather"] },
      },
      { type: GuardrailTypes.PROMPT_INJECTION, config: {} },
    ]);
  });

  it("round-trips a stored policy's rules", () => {
    const rules: GuardrailPolicyRule[] = [
      { type: GuardrailTypes.PII, config: { entities: ["EMAIL_ADDRESS"] } },
      {
        type: GuardrailTypes.TOPIC,
        config: {
          allowed_topics: ["billing"],
          restricted_topics: ["politics"],
        },
      },
      { type: GuardrailTypes.PROMPT_INJECTION, config: {} },
    ];

    expect(toRulesPayload(toRulesState(rules))).toEqual(rules);
  });
});

describe("validateRules", () => {
  it("accepts the defaults, where nothing is switched on", () => {
    expect(validateRules(DEFAULT_RULES_STATE)).toEqual({});
  });

  it("accepts restricted and allowed topics together", () => {
    expect(validateRules(withTopics(["politics"], ["billing"]))).toEqual({});
  });

  it("asks for at least one personal data class", () => {
    const errors = validateRules(
      withRules({ [GuardrailTypes.PII]: { enabled: true, entities: [] } }),
    );

    expect(errors["pii.entities"]).toBe(
      "Select at least one category of personal data",
    );
  });

  it.each([
    [[" ", ""], [], "Enter at least one restricted or allowed topic"],
    [
      [],
      ["a".repeat(MAX_TOPIC_LENGTH + 1)],
      `A topic can be at most ${MAX_TOPIC_LENGTH} characters`,
    ],
    [
      ["politics", " politics"],
      [],
      "Each topic can appear only once in a list",
    ],
    [["Politics", "politics"], [], "Each topic can appear only once in a list"],
    [
      [],
      ["None of these"],
      '"none of these" is reserved: the check adds it to every list itself',
    ],
    [["Billing"], ["billing"], "A topic cannot be both restricted and allowed"],
  ])(
    "rejects restricted %j with allowed %j",
    (restricted, allowed, message) => {
      const errors = validateRules(withTopics(restricted, allowed));

      expect(errors["topic.topics"]).toBe(message);
    },
  );
});
