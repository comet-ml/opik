import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import copy from "clipboard-copy";

import PlaygroundErrorBoundary, {
  clearSavedPlaygroundState,
} from "./PlaygroundErrorBoundary";
import usePlaygroundStore from "@/store/PlaygroundStore";
import {
  PLAYGROUND_OUTPUT_COLUMNS_WIDTH_KEY,
  PLAYGROUND_OUTPUT_LEFT_PANEL_WIDTH_KEY,
  PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
} from "@/constants/llm";

vi.mock("clipboard-copy", () => ({ default: vi.fn(() => Promise.resolve()) }));

const toast = vi.hoisted(() => vi.fn());
vi.mock("@/ui/use-toast", () => ({ useToast: () => ({ toast }) }));

const PromptModels = () => {
  const promptIds = usePlaygroundStore((state) => state.promptIds);
  const promptMap = usePlaygroundStore((state) => state.promptMap);

  return (
    <div>
      {promptIds.map((id) => promptMap[id].model).join(", ") || "No prompts"}
    </div>
  );
};

const renderPlayground = () =>
  render(
    <PlaygroundErrorBoundary>
      <PromptModels />
    </PlaygroundErrorBoundary>,
  );

describe("PlaygroundErrorBoundary", () => {
  beforeEach(() => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    usePlaygroundStore.setState({ promptIds: ["missing"], promptMap: {} });
    localStorage.setItem(PLAYGROUND_SELECTED_DATASET_VERSION_KEY, '"d1::v1"');
    localStorage.setItem(PLAYGROUND_OUTPUT_COLUMNS_WIDTH_KEY, "{}");
    localStorage.setItem(PLAYGROUND_OUTPUT_LEFT_PANEL_WIDTH_KEY, '"50%"');
  });

  afterEach(() => {
    clearSavedPlaygroundState();
    vi.clearAllMocks();
    vi.restoreAllMocks();
  });

  it("shows the recovery screen with the error when the playground throws", () => {
    renderPlayground();

    expect(screen.getByText("The playground crashed")).toBeInTheDocument();
    expect(screen.getByText(/reading 'model'/)).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Report on GitHub/ }),
    ).toHaveAttribute("href", "https://github.com/comet-ml/opik/issues/new");
  });

  it("clears the saved playground state on Reset and renders the playground again", () => {
    expect(localStorage.getItem("PLAYGROUND_STATE")).toContain("missing");
    renderPlayground();

    fireEvent.click(screen.getByRole("button", { name: /Reset playground/ }));

    expect(screen.getByText("No prompts")).toBeInTheDocument();
    expect(usePlaygroundStore.getState().promptIds).toEqual([]);
    expect(localStorage.getItem("PLAYGROUND_STATE")).toBeNull();
    expect(
      localStorage.getItem(PLAYGROUND_SELECTED_DATASET_VERSION_KEY),
    ).toBeNull();
    expect(
      localStorage.getItem(PLAYGROUND_OUTPUT_COLUMNS_WIDTH_KEY),
    ).toBeNull();
    expect(
      localStorage.getItem(PLAYGROUND_OUTPUT_LEFT_PANEL_WIDTH_KEY),
    ).toBeNull();
  });

  it("copies the error message, stack and component stack", async () => {
    renderPlayground();

    fireEvent.click(screen.getByRole("button", { name: /Copy details/ }));

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith({
        description: "Error details copied",
      }),
    );
    const details = vi.mocked(copy).mock.calls[0][0];
    expect(details).toMatch(/^TypeError: .*reading 'model'/);
    expect(details).toContain("Component stack:");
    expect(details).toContain("PromptModels");
  });
});
