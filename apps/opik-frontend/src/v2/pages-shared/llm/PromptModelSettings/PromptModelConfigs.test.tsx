import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import PromptModelConfigs from "./PromptModelConfigs";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const renderTrigger = (provider: COMPOSED_PROVIDER_TYPE) =>
  render(
    <TooltipProvider delayDuration={700}>
      <PromptModelConfigs
        provider={provider}
        model={PROVIDER_MODEL_TYPE.OPIK_FREE_MODEL}
        configs={{}}
        onChange={vi.fn()}
      />
    </TooltipProvider>,
  );

describe("PromptModelConfigs trigger", () => {
  it.each([
    ["the Opik free model", PROVIDER_TYPE.OPIK_FREE],
    ["Ollama", `${PROVIDER_TYPE.OLLAMA}:local`],
    ["Bedrock", `${PROVIDER_TYPE.BEDROCK}:aws`],
  ])("is hidden for %s, which has no parameters to set", (_, provider) => {
    renderTrigger(provider);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("is enabled for a provider with parameters", () => {
    renderTrigger(PROVIDER_TYPE.OPEN_AI);

    expect(screen.getByRole("button")).toBeEnabled();
  });

  it("stays disabled before a provider is picked", () => {
    renderTrigger("");

    expect(screen.getByRole("button")).toBeDisabled();
  });
});
