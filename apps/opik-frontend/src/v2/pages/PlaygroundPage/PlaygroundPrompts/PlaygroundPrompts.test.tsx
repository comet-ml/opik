import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import PlaygroundPrompts from "./PlaygroundPrompts";

let promptIds: string[];

vi.mock("@/store/PlaygroundStore", () => ({
  usePromptCount: () => promptIds.length,
  usePromptIds: () => promptIds,
  useSetPromptMap: () => vi.fn(),
}));

vi.mock("@/hooks/useLastPickedModel", () => ({
  default: () => [undefined, vi.fn()],
}));

vi.mock("@/hooks/useLLMProviderModelsData", () => ({
  default: () => ({
    calculateModelProvider: vi.fn(),
    calculateDefaultModel: vi.fn(),
  }),
}));

vi.mock("@/v2/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompt", () => ({
  default: ({
    promptId,
    onRun,
    onStop,
  }: {
    promptId: string;
    onRun?: () => void;
    onStop?: () => void;
  }) =>
    onRun && onStop ? (
      <>
        <button onClick={onRun}>Run {promptId}</button>
        <button onClick={onStop}>Stop {promptId}</button>
      </>
    ) : null,
}));

const runSingle = vi.fn();
const stopSingle = vi.fn();

const renderPrompts = () =>
  render(
    <PlaygroundPrompts
      workspaceName="default"
      providerKeys={[]}
      isPendingProviderKeys={false}
      hasLoadedProviderKeys
      runSingle={runSingle}
      stopSingle={stopSingle}
    />,
  );

beforeEach(() => {
  runSingle.mockClear();
  stopSingle.mockClear();
});

describe("PlaygroundPrompts", () => {
  it("should not offer a per-prompt Run when there is only one prompt", () => {
    promptIds = ["a"];

    renderPrompts();

    expect(screen.queryAllByRole("button")).toHaveLength(0);
  });

  it("should offer a per-prompt Run and Stop that act on that prompt only when there are several", () => {
    promptIds = ["a", "b"];

    renderPrompts();
    fireEvent.click(screen.getByRole("button", { name: "Run b" }));
    fireEvent.click(screen.getByRole("button", { name: "Stop b" }));

    expect(screen.getByRole("button", { name: "Run a" })).toBeInTheDocument();
    expect(runSingle).toHaveBeenCalledTimes(1);
    expect(runSingle).toHaveBeenCalledWith("b");
    expect(stopSingle).toHaveBeenCalledTimes(1);
    expect(stopSingle).toHaveBeenCalledWith("b");
  });
});
