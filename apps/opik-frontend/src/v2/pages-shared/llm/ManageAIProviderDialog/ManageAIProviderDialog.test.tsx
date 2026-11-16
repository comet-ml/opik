import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

import ManageAIProviderDialog from "./ManageAIProviderDialog";
import { PROVIDER_TYPE, ProviderObject } from "@/types/providers";
import { TooltipProvider } from "@/ui/tooltip";

const { updateProviderKey } = vi.hoisted(() => ({
  updateProviderKey: vi.fn(),
}));

vi.mock("@/api/provider-keys/useProviderKeysUpdateMutation", () => ({
  default: () => ({ mutate: updateProviderKey }),
}));

vi.mock("@/api/provider-keys/useProviderKeysCreateMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/api/provider-keys/useProviderKeysDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/api/provider-keys/useProviderKeysAuthCheckMutation", () => ({
  default: () => ({ mutate: vi.fn(), isPending: false }),
}));

vi.mock("@/hooks/useProviderOptions", () => ({
  useProviderOptions: () => [],
}));

const storedBedrockProvider = {
  id: "bedrock-id",
  created_at: "2026-11-03T00:00:00Z",
  provider: PROVIDER_TYPE.BEDROCK,
  ui_composed_provider: "bedrock:bedrock-claude",
  provider_name: "bedrock-claude",
  base_url: "https://bedrock-runtime.us-east-2.amazonaws.com/anthropic",
  api_key: "ABS*****************xyz",
  configuration: {
    models: "us.anthropic.claude-haiku-4-5",
    bedrock_api: "anthropic_messages",
  },
  read_only: false,
} as unknown as ProviderObject;

const editAndSwitchToConverse = () => {
  render(
    <TooltipProvider>
      <ManageAIProviderDialog
        open
        setOpen={vi.fn()}
        providerKey={storedBedrockProvider}
      />
    </TooltipProvider>,
  );
  fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
  fireEvent.keyDown(screen.getByRole("option", { name: /Converse/ }), {
    key: "Enter",
  });
};

describe("switching the API of a stored Bedrock provider", () => {
  beforeEach(() => {
    updateProviderKey.mockReset();
  });

  it("asks for the API key again instead of sending a URL change without it", async () => {
    editAndSwitchToConverse();
    fireEvent.click(
      screen.getByRole("button", { name: "Update configuration" }),
    );

    expect(
      await screen.findByText("Enter the API key again to change the URL"),
    ).toBeInTheDocument();
    expect(updateProviderKey).not.toHaveBeenCalled();
  });

  it("saves the new URL together with the key once it is entered", async () => {
    editAndSwitchToConverse();
    fireEvent.change(screen.getByPlaceholderText("API key"), {
      target: { value: "new-bedrock-key" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Update configuration" }),
    );

    await waitFor(() => expect(updateProviderKey).toHaveBeenCalledTimes(1));
    const { providerKey } = updateProviderKey.mock.calls[0][0];
    expect(providerKey.base_url).toBe(
      "https://bedrock-runtime.us-east-2.amazonaws.com",
    );
    expect(providerKey.apiKey).toBe("new-bedrock-key");
    expect(providerKey.configuration.bedrock_api).toBe("converse");
  });
});
