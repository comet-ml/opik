import { describe, expect, it } from "vitest";

import evaluatePromptsCode from "@/v2/pages-shared/onboarding/EvaluationExamples/evaluation-scripts/EvaluatePrompts.py?raw";
import { INTEGRATIONS } from "./integrations";

// OpenAI shuts gpt-3.5-turbo down on 2026-10-23, so snippets users copy must not name it.
const RETIRED_MODEL = "gpt-3.5-turbo";
const CURRENT_MODEL = "gpt-5.6-terra";

const integrationCode = (id: string): string => {
  const integration = INTEGRATIONS.find((item) => item.id === id);
  if (!integration) {
    throw new Error(`Integration "${id}" not found`);
  }
  return [
    integration.code,
    ...(integration.additionalSteps ?? []).map((step) => step.code),
  ].join("\n");
};

const ONBOARDING_SNIPPET = "onboarding/EvaluatePrompts";

const OPENAI_MODEL_SNIPPETS: [string, string][] = [
  ...[
    "function-decorators",
    "openai",
    "haystack",
    "litellm",
    "ragas",
    "guardrailsai",
  ].map((id): [string, string] => [id, integrationCode(id)]),
  [ONBOARDING_SNIPPET, evaluatePromptsCode],
];

const ALL_SNIPPETS: [string, string][] = [
  ...INTEGRATIONS.map((item): [string, string] => [
    item.id,
    integrationCode(item.id),
  ]),
  [ONBOARDING_SNIPPET, evaluatePromptsCode],
];

describe("published setup snippets", () => {
  it.each(OPENAI_MODEL_SNIPPETS)(
    "%s should name the current OpenAI model",
    (_, code) => {
      expect(code).toContain(`"${CURRENT_MODEL}"`);
    },
  );

  it("ragas should pass the current model to ChatOpenAI", () => {
    expect(integrationCode("ragas")).toContain(
      `ChatOpenAI(model="${CURRENT_MODEL}")`,
    );
  });

  it.each(ALL_SNIPPETS)("%s should not name a retired model", (_, code) => {
    expect(code).not.toContain(RETIRED_MODEL);
  });
});
