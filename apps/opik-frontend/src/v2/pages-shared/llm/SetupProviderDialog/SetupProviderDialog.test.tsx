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

vi.mock("@/api/provider-keys/useProviderKeysAuthCheckMutation", () => ({
  default: () => ({ mutate: vi.fn(), isPending: false }),
}));

vi.mock("@/hooks/useProviderOptions", () => ({
  useProviderOptions: () => [
    {
      value: PROVIDER_TYPE.OPEN_AI,
      label: "OpenAI",
      providerType: PROVIDER_TYPE.OPEN_AI,
    },
    {
      value: PROVIDER_TYPE.BEDROCK,
      label: "Bedrock",
      providerType: PROVIDER_TYPE.BEDROCK,
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

  it("shows and saves Chat Completions by default", async () => {
    startOpenAiSetup();

    expect(screen.getByRole("combobox")).toHaveTextContent(
      "Chat Completions API",
    );
    expect(await savedConfiguration()).toEqual({
      openai_pipeline_mode: "chat_completions_api",
    });
  });

  it("saves the Responses API when the user picks it", async () => {
    startOpenAiSetup();
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

const startBedrockSetup = () => {
  render(
    <TooltipProvider>
      <SetupProviderDialog open setOpen={vi.fn()} />
    </TooltipProvider>,
  );
  fireEvent.click(screen.getByText("Bedrock"));
  fireEvent.change(screen.getByPlaceholderText("Bedrock us-east-1"), {
    target: { value: "bedrock-eu" },
  });
  fireEvent.change(screen.getByPlaceholderText("Models list"), {
    target: { value: "eu.anthropic.claude-haiku-5-5" },
  });
  fireEvent.change(screen.getByPlaceholderText("API key"), {
    target: { value: "bedrock-unit-test" },
  });
};

const bedrockApiSelect = () => screen.getByRole("combobox", { name: "API" });

describe("adding a Bedrock provider from the playground setup dialog", () => {
  beforeEach(() => {
    createProviderKey.mockReset();
  });

  it("shows and saves Chat Completions by default", async () => {
    startBedrockSetup();
    fireEvent.change(
      screen.getByPlaceholderText(
        "https://bedrock-runtime.us-east-1.amazonaws.com/openai/v1",
      ),
      {
        target: {
          value: "https://bedrock-runtime.eu-west-1.amazonaws.com/openai/v1",
        },
      },
    );

    expect(bedrockApiSelect()).toHaveTextContent("Chat Completions");
    expect(await savedConfiguration()).toEqual({
      models: "custom-llm/bedrock-eu/eu.anthropic.claude-haiku-5-5",
      bedrock_api: "chat_completions",
    });
  });

  it("saves Anthropic Messages and suggests its URL when the user picks it", async () => {
    startBedrockSetup();
    fireEvent.keyDown(bedrockApiSelect(), { key: "Enter" });
    fireEvent.keyDown(
      screen.getByRole("option", { name: "Anthropic Messages (Claude)" }),
      { key: "Enter" },
    );
    fireEvent.change(
      screen.getByPlaceholderText(
        "https://bedrock-runtime.us-east-1.amazonaws.com/anthropic",
      ),
      {
        target: {
          value: "https://bedrock-runtime.eu-west-1.amazonaws.com/anthropic",
        },
      },
    );

    expect(bedrockApiSelect()).toHaveTextContent("Anthropic Messages");
    expect(await savedConfiguration()).toEqual({
      models: "custom-llm/bedrock-eu/eu.anthropic.claude-haiku-5-5",
      bedrock_api: "anthropic_messages",
    });
  });

  it("saves Converse and suggests the plain region URL when the user picks it", async () => {
    startBedrockSetup();
    fireEvent.keyDown(bedrockApiSelect(), { key: "Enter" });
    fireEvent.keyDown(
      screen.getByRole("option", { name: "Converse (Amazon Nova and others)" }),
      { key: "Enter" },
    );
    fireEvent.change(
      screen.getByPlaceholderText(
        "https://bedrock-runtime.us-east-1.amazonaws.com",
      ),
      {
        target: { value: "https://bedrock-runtime.eu-west-1.amazonaws.com" },
      },
    );

    expect(bedrockApiSelect()).toHaveTextContent("Converse");
    expect(await savedConfiguration()).toEqual({
      models: "custom-llm/bedrock-eu/eu.anthropic.claude-haiku-5-5",
      bedrock_api: "converse",
    });
  });
});
