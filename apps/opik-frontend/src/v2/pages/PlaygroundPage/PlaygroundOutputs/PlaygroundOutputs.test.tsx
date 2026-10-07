import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import PlaygroundOutputs from "./PlaygroundOutputs";

let promptIds: string[];

vi.mock("@/store/PlaygroundStore", () => ({
  usePromptIds: () => promptIds,
  useSetDatasetVariables: () => vi.fn(),
  useSetDatasetSampleData: () => vi.fn(),
  useSetDatasetItemsTotal: () => vi.fn(),
  useDatasetFilters: () => [],
  useDatasetPage: () => 1,
  useSetDatasetPage: () => vi.fn(),
  useDatasetSize: () => 10,
  useSetDatasetSize: () => vi.fn(),
}));

vi.mock("@/api/datasets/useDatasetItemsList", () => ({
  default: () => ({
    data: undefined,
    isLoading: false,
    isPlaceholderData: false,
    isFetching: false,
  }),
}));

vi.mock("@/api/datasets/useDatasetById", () => ({
  default: () => ({ data: undefined }),
}));

vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundPromptOutput",
  () => ({
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
  }),
);

const runSingle = vi.fn();
const stopSingle = vi.fn();

const renderOutputs = () =>
  render(
    <PlaygroundOutputs
      datasetId={null}
      runSingle={runSingle}
      stopSingle={stopSingle}
    />,
  );

beforeEach(() => {
  runSingle.mockClear();
  stopSingle.mockClear();
});

describe("PlaygroundOutputs", () => {
  it("should not offer a per-prompt Run when there is only one prompt", () => {
    promptIds = ["a"];

    renderOutputs();

    expect(screen.queryAllByRole("button")).toHaveLength(0);
  });

  it("should offer a per-prompt Run and Stop that act on that prompt only when there are several", () => {
    promptIds = ["a", "b"];

    renderOutputs();
    fireEvent.click(screen.getByRole("button", { name: "Run b" }));
    fireEvent.click(screen.getByRole("button", { name: "Stop b" }));

    expect(screen.getByRole("button", { name: "Run a" })).toBeInTheDocument();
    expect(runSingle).toHaveBeenCalledTimes(1);
    expect(runSingle).toHaveBeenCalledWith("b");
    expect(stopSingle).toHaveBeenCalledTimes(1);
    expect(stopSingle).toHaveBeenCalledWith("b");
  });
});
