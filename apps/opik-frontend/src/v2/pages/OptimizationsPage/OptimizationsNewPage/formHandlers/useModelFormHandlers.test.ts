import { describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useForm } from "react-hook-form";

import { useModelFormHandlers } from "./useModelFormHandlers";
import { OptimizationConfigFormType } from "@/v2/pages-shared/optimizations/OptimizationConfigForm/schema";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { getProviderFromModel } from "@/lib/provider";

vi.mock("@/hooks/useLLMProviderModelsData", () => ({
  default: () => ({
    calculateModelProvider: (model: PROVIDER_MODEL_TYPE) =>
      getProviderFromModel(model),
  }),
}));

const switchModel = (
  from: PROVIDER_MODEL_TYPE,
  modelConfig: Record<string, unknown>,
  to: PROVIDER_MODEL_TYPE,
) => {
  const { result } = renderHook(() => {
    const form = useForm<OptimizationConfigFormType>({
      defaultValues: {
        modelName: from,
        modelConfig,
      } as unknown as OptimizationConfigFormType,
    });
    return { form, handlers: useModelFormHandlers(form) };
  });

  act(() => {
    result.current.handlers.handleModelChange(to);
  });

  return result.current.form.getValues("modelConfig") as Record<
    string,
    unknown
  >;
};

describe("useModelFormHandlers on a model switch", () => {
  it.each<
    [
      string,
      PROVIDER_MODEL_TYPE,
      Record<string, unknown>,
      PROVIDER_MODEL_TYPE,
      Record<string, unknown>,
    ]
  >([
    [
      "moves a picked Minimal on Gemini 3 Flash to Low on 2.5 Flash",
      PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      { thinkingLevel: "minimal" },
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      { thinkingLevel: "low" },
    ],
    [
      "moves a picked xHigh on Opus 4.7 to High on Sonnet 4.6",
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
      { thinkingEffort: "xhigh" },
      PROVIDER_MODEL_TYPE.CLAUDE_SONNET_4_6,
      { thinkingEffort: "high" },
    ],
    [
      "moves a picked Minimal on GPT 5 Nano to Low on GPT 5.1",
      PROVIDER_MODEL_TYPE.GPT_5_NANO,
      { reasoningEffort: "minimal" },
      PROVIDER_MODEL_TYPE.GPT_5_1,
      { reasoningEffort: "low" },
    ],
    [
      "gives 2.5 Flash its own default for a level that was only Gemini 3 Pro's default",
      PROVIDER_MODEL_TYPE.GEMINI_3_PRO,
      { thinkingLevel: "high" },
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
      { thinkingLevel: "auto" },
    ],
    [
      "starts from the new model's default when the provider changes",
      PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      { thinkingLevel: "minimal" },
      PROVIDER_MODEL_TYPE.VERTEX_AI_GEMINI_2_5_FLASH,
      { thinkingLevel: "auto" },
    ],
  ])("%s", (_, from, modelConfig, to, expected) => {
    expect(switchModel(from, modelConfig, to)).toMatchObject(expected);
  });

  it("does not carry the other settings over", () => {
    const config = switchModel(
      PROVIDER_MODEL_TYPE.GEMINI_3_FLASH,
      { thinkingLevel: "minimal", temperature: 0.9 },
      PROVIDER_MODEL_TYPE.GEMINI_2_5_FLASH,
    );

    expect(config.temperature).not.toBe(0.9);
  });
});
