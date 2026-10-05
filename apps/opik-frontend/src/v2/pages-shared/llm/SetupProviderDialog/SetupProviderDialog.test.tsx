import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import SetupProviderDialog from "./SetupProviderDialog";
import { PROVIDER_TYPE } from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const { createProviderKey } = vi.hoisted(() => ({
  createProviderKey: vi.fn(),
}));

vi.mock("@/api/provider-keys/useProviderKeysCreateMutation", () => ({
  default: () => ({ mutate: createProviderKey }),
}));

vi.mock("@/hooks/useProviderOptions", () => ({
  useProviderOptions: () => [
    {
      value: PROVIDER_TYPE.OPEN_AI,
      label: "OpenAI",
      providerType: PROVIDER_TYPE.OPEN_AI,
    },
  ],
}));

const startOpenAiSetup = () => {
  render(
    <TooltipProvider>
      <SetupProviderDialog open setOpen={vi.fn()} />
    </TooltipProvider>,
  );
  fireEvent.click(screen.getByText("OpenAI"));
  fireEvent.change(screen.getByPlaceholderText("OpenAI API Key"), {
    target: { value: "sk-unit-test" },
  });
};

const savedConfiguration = async () => {
  fireEvent.click(screen.getByRole("button", { name: "Add provider" }));
  await waitFor(() => expect(createProviderKey).toHaveBeenCalledTimes(1));
  return createProviderKey.mock.calls[0][0].providerKey.configuration;
};

describe("adding an OpenAI key from the playground setup dialog", () => {
  beforeEach(() => {
    createProviderKey.mockReset();
  });

  it("saves Chat Completions when the advanced settings are left alone", async () => {
    startOpenAiSetup();

    expect(screen.queryByText("OpenAI API")).not.toBeInTheDocument();
    expect(await savedConfiguration()).toEqual({
      openai_pipeline_mode: "chat_completions_api",
    });
  });

  it("saves the Responses API when the user picks it", async () => {
    startOpenAiSetup();
    fireEvent.click(screen.getByText("Advanced settings"));
    fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
    fireEvent.keyDown(screen.getByRole("option", { name: "Responses API" }), {
      key: "Enter",
    });

    expect(screen.getByRole("combobox")).toHaveTextContent("Responses API");
    expect(await savedConfiguration()).toEqual({
      openai_pipeline_mode: "responses_api",
    });
  });
});
