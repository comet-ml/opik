import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

vi.mock("@uiw/react-codemirror", () => ({
  default: () => <div data-testid="codemirror-stub" />,
}));

import OpenAIModelConfigs from "./OpenAIModelConfigs";
import CustomModelConfigs from "./CustomModelConfig";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const CONFIG = {
  temperature: 0,
  maxCompletionTokens: 4000,
  topP: 1,
  frequencyPenalty: 0,
  presencePenalty: 0,
};

const PANELS = {
  OpenAI: (onChange: () => void) => (
    <OpenAIModelConfigs
      configs={CONFIG}
      model={PROVIDER_MODEL_TYPE.GPT_4O_MINI}
      onChange={onChange}
    />
  ),
  Custom: (onChange: () => void) => (
    <CustomModelConfigs
      configs={CONFIG}
      model={"custom-llm/mock/mock-model" as PROVIDER_MODEL_TYPE}
      onChange={onChange}
    />
  ),
};

const typeAndBlur = (id: string, value: string) => {
  const input = screen.getByTestId(`${id}-input`);
  fireEvent.change(input, { target: { value } });
  fireEvent.blur(input);
};

describe.each(Object.entries(PANELS))(
  "%s panel sampling ranges",
  (_, panel) => {
    it.each([
      ["temperature", "2", 2],
      ["temperature", "3", 2],
      ["frequencyPenalty", "-2", -2],
      ["frequencyPenalty", "-3", -2],
      ["presencePenalty", "2", 2],
      ["presencePenalty", "-2", -2],
    ])("saves %s typed as %s as %d", (id, typed, saved) => {
      const onChange = vi.fn();
      render(<TooltipProvider>{panel(onChange)}</TooltipProvider>);

      typeAndBlur(id, typed);

      expect(onChange).toHaveBeenCalledWith({ [id]: saved });
    });
  },
);
