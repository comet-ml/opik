import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import React from "react";

import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import LLMJudgeRuleDetails from "./LLMJudgeRuleDetails";

const setValue = vi.fn();
const getValues = vi.fn(() => ({ temperature: 0, maxCompletionTokens: 1024 }));
const fieldOnChange = vi.fn();
let promptModelSelectProps: {
  onChange: (model: PROVIDER_MODEL_TYPE, provider: PROVIDER_TYPE) => void;
};
let promptModelConfigsProvider: string | undefined;
const updateProviderConfig = vi.fn(
  (config: Record<string, unknown>, params: { provider: PROVIDER_TYPE }) => ({
    ...config,
    providerSeen: params.provider,
  }),
);

vi.mock("@/hooks/useLLMProviderModelsData", () => ({
  default: () => ({
    calculateModelProvider: vi.fn(() => PROVIDER_TYPE.GEMINI),
    calculateDefaultModel: vi.fn(
      () => PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
    ),
  }),
}));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => false,
}));

vi.mock("@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect", () => ({
  default: (props: typeof promptModelSelectProps) => {
    promptModelSelectProps = props;
    return (
      <button
        onClick={() =>
          props.onChange(
            PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
            PROVIDER_TYPE.VERTEX_AI,
          )
        }
      >
        Select Vertex model
      </button>
    );
  },
}));

vi.mock("@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs", () => ({
  default: (props: { provider: string }) => {
    promptModelConfigsProvider = props.provider;
    return null;
  },
}));
vi.mock("@/lib/modelUtils", () => ({
  updateProviderConfig: (...args: Parameters<typeof updateProviderConfig>) =>
    updateProviderConfig(...args),
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
vi.mock("@/shared/ExplainerIcon/ExplainerIcon", () => ({
  default: () => null,
}));
vi.mock("@/shared/TooltipWrapper/TooltipWrapper", () => ({
  default: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock("@/shared/SelectBox/SelectBox", () => ({ default: () => null }));

const form = {
  control: {},
  watch: vi.fn((name: string) => {
    if (name === "scope") {
      return "trace";
    }
    return "";
  }),
  getValues,
  setValue,
} as never;

vi.mock("@/ui/form", () => ({
  FormControl: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
  FormItem: ({ children }: { children: React.ReactNode }) => (
    <div>{children}</div>
  ),
  FormMessage: () => null,
  FormField: ({
    render,
    name,
  }: {
    render: (args: unknown) => React.ReactNode;
    name: string;
  }) =>
    render({
      field: {
        value: name === "llmJudgeDetails.model" ? "" : undefined,
        onChange: fieldOnChange,
      },
      formState: { errors: {} },
    }),
}));

describe("LLMJudgeRuleDetails", () => {
  it("uses the provider resolved by PromptModelSelect when updating model config", () => {
    render(<LLMJudgeRuleDetails workspaceName="workspace" form={form} />);

    expect(promptModelConfigsProvider).toBe(PROVIDER_TYPE.GEMINI);

    fireEvent.click(screen.getByText("Select Vertex model"));

    expect(fieldOnChange).toHaveBeenCalledWith(
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
    );
    expect(updateProviderConfig).toHaveBeenCalledWith(
      { temperature: 0, maxCompletionTokens: 1024 },
      {
        model: PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_PRO,
        provider: PROVIDER_TYPE.VERTEX_AI,
      },
    );
    expect(setValue).toHaveBeenCalledWith("llmJudgeDetails.config", {
      temperature: 0,
      maxCompletionTokens: 1024,
      providerSeen: PROVIDER_TYPE.VERTEX_AI,
    });
  });
});
