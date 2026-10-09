import { describe, it, expect, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { useForm, UseFormReturn } from "react-hook-form";

import { Form } from "@/ui/form";
import { TooltipProvider } from "@/ui/tooltip";
import { LLM_JUDGE } from "@/types/llm";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { COMPOSED_PROVIDER_TYPE, PROVIDER_MODEL_TYPE } from "@/types/providers";
import { EvaluationRuleFormType } from "./schema";
import LLMJudgeRuleDetails from "./LLMJudgeRuleDetails";

const PROVIDER_ROWS: Record<string, COMPOSED_PROVIDER_TYPE> = {
  "custom-llm/mock/mock-model": "custom-llm:mock" as COMPOSED_PROVIDER_TYPE,
  "custom-llm/mock/other-model": "custom-llm:mock" as COMPOSED_PROVIDER_TYPE,
  "custom-llm/gw/gw-model": "custom-llm:gw" as COMPOSED_PROVIDER_TYPE,
  "custom-llm/local/qwen3:0.6b": "ollama:local" as COMPOSED_PROVIDER_TYPE,
  "custom-llm/local/llama3.2:1b": "ollama:local" as COMPOSED_PROVIDER_TYPE,
  "openai/gpt-4o-mini": "openrouter" as COMPOSED_PROVIDER_TYPE,
};

vi.mock("@/hooks/useLLMProviderModelsData", () => ({
  default: () => ({
    calculateModelProvider: (model?: string) =>
      (model && PROVIDER_ROWS[model]) || "",
    calculateDefaultModel: vi.fn(),
  }),
}));

// The real picker reports the bare provider type, never the row's key.
vi.mock("@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect", () => ({
  default: ({
    onChange,
  }: {
    onChange: (model: PROVIDER_MODEL_TYPE, provider: string) => void;
  }) => (
    <>
      {Object.entries(PROVIDER_ROWS).map(([model, row]) => (
        <button
          key={model}
          onClick={() =>
            onChange(model as PROVIDER_MODEL_TYPE, row.split(":")[0])
          }
        >
          {model}
        </button>
      ))}
    </>
  ),
}));

vi.mock("@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages", () => ({
  default: () => null,
}));
vi.mock(
  "@/v2/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariables",
  () => ({ default: () => null }),
);
vi.mock("@/v2/pages-shared/llm/LLMJudgeScores/LLMJudgeScores", () => ({
  default: () => null,
}));

const EXTRA_BODY = { max_tokens: 66, marker: "D" };

const renderRule = (model: string) => {
  let form: UseFormReturn<EvaluationRuleFormType> | undefined;
  const Harness = () => {
    form = useForm<EvaluationRuleFormType>({
      defaultValues: {
        scope: EVALUATORS_RULE_SCOPE.trace,
        projectIds: [],
        llmJudgeDetails: {
          model,
          config: {
            temperature: 0.2,
            seed: null,
            custom_parameters: EXTRA_BODY,
          },
          template: LLM_JUDGE.custom,
          messages: [],
          variables: {},
          schema: [],
          maxCostUsd: null,
        },
      } as unknown as EvaluationRuleFormType,
    });
    return (
      <TooltipProvider>
        <Form {...form}>
          <LLMJudgeRuleDetails workspaceName="default" form={form} />
        </Form>
      </TooltipProvider>
    );
  };
  render(<Harness />);
  return () => form!.getValues("llmJudgeDetails.config.custom_parameters");
};

describe("LLMJudgeRuleDetails extra body on a model switch", () => {
  it.each([
    [
      "the same Custom LLM row",
      "custom-llm/mock/mock-model",
      "custom-llm/mock/other-model",
    ],
    [
      "the same Ollama row",
      "custom-llm/local/qwen3:0.6b",
      "custom-llm/local/llama3.2:1b",
    ],
  ])("keeps it between two models of %s", (_label, from, to) => {
    const extraBody = renderRule(from);

    fireEvent.click(screen.getByRole("button", { name: to }));

    expect(extraBody()).toEqual(EXTRA_BODY);
  });

  it.each([
    ["another Custom LLM row", "custom-llm/gw/gw-model"],
    ["another provider", "openai/gpt-4o-mini"],
  ])("drops it on a switch to %s", (_label, to) => {
    const extraBody = renderRule("custom-llm/mock/mock-model");

    fireEvent.click(screen.getByRole("button", { name: to }));

    expect(extraBody()).toBeNull();
  });
});
