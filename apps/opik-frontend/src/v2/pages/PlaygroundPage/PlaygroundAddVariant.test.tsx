import { describe, it, expect, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { PlaygroundPromptType } from "@/types/playground";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import PlaygroundAddVariant from "./PlaygroundAddVariant";

const addPrompt = vi.fn();

vi.mock("@/store/PlaygroundStore", () => ({
  useAddPrompt: () => addPrompt,
}));

vi.mock("@/hooks/useLastPickedModel", () => ({
  default: () => [PROVIDER_MODEL_TYPE.GPT_4O_MINI],
}));

vi.mock("@/hooks/useLLMProviderModelsData", () => ({
  default: () => ({
    calculateModelProvider: () => PROVIDER_TYPE.OPEN_AI,
    calculateDefaultModel: (model: PROVIDER_MODEL_TYPE) => model,
  }),
}));

describe("PlaygroundAddVariant", () => {
  it("should add one blank variant at the end on a single click", () => {
    render(<PlaygroundAddVariant providerKeys={[PROVIDER_TYPE.OPEN_AI]} />);

    fireEvent.click(screen.getByTestId("playground-add-variant-button"));

    expect(addPrompt).toHaveBeenCalledTimes(1);
    const [added, position] = addPrompt.mock.calls[0] as [
      PlaygroundPromptType,
      number | undefined,
    ];
    expect(position).toBeUndefined();
    expect(added.model).toBe(PROVIDER_MODEL_TYPE.GPT_4O_MINI);
    expect(
      added.messages.map(({ role, content }) => ({ role, content })),
    ).toEqual([
      { role: LLM_MESSAGE_ROLE.system, content: "" },
      { role: LLM_MESSAGE_ROLE.user, content: "" },
    ]);
    expect(screen.queryByText("Duplicate variant")).not.toBeInTheDocument();
  });
});
