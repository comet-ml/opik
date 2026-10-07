import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { TooltipProvider } from "@/ui/tooltip";
import usePlaygroundStore from "@/store/PlaygroundStore";
import PlaygroundExperimentName from "./PlaygroundExperimentName";

const patch = vi.fn();
const get = vi.fn();
const toast = vi.fn();

vi.mock("@/api/api", () => ({
  default: {
    patch: (...args: unknown[]) => patch(...args),
    get: (...args: unknown[]) => get(...args),
  },
  EXPERIMENTS_REST_ENDPOINT: "/v1/private/experiments/",
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast }),
}));

const DATASET_ID = "dataset-1";

const renderName = (datasetId = DATASET_ID) =>
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { mutations: { retry: false } } })
      }
    >
      <TooltipProvider>
        <PlaygroundExperimentName datasetId={datasetId} />
      </TooltipProvider>
    </QueryClientProvider>,
  );

const commitName = (name: string, current = /foo|Auto-generated/) => {
  fireEvent.click(screen.getByRole("button", { name: current }));
  const input = screen.getByRole("textbox");
  fireEvent.change(input, { target: { value: name } });
  fireEvent.keyDown(input, { key: "Enter" });
};

const finishedRun = (name: string | null) =>
  usePlaygroundStore.setState({
    promptIds: ["p1", "p2"],
    experimentName: name,
    lastSuggestedExperimentName: null,
    lastRun: {
      name,
      datasetId: DATASET_ID,
      experiments: [
        { id: "e1", index: 0 },
        { id: "e2", index: 1 },
      ],
    },
  });

const editor = () => screen.getByTestId("playground-experiment-name-editor");
const preview = () => screen.getByTestId("playground-experiment-name-preview");

describe("PlaygroundExperimentName", () => {
  beforeEach(() => {
    patch.mockReset();
    get.mockReset();
    toast.mockReset();
    localStorage.clear();
  });

  it("renames the last run's experiments to the committed name", async () => {
    patch.mockResolvedValue({});
    finishedRun("foo");
    renderName();

    expect(screen.getByText("Last run:")).toBeInTheDocument();
    expect(preview()).toHaveTextContent("Created: foo_a");

    commitName("bar");

    await waitFor(() =>
      expect(usePlaygroundStore.getState().experimentName).toBe("bar"),
    );
    expect(patch.mock.calls).toEqual([
      ["/v1/private/experiments/e1", { name: "bar_a" }],
      ["/v1/private/experiments/e2", { name: "bar_b" }],
    ]);
    expect(editor()).toHaveTextContent("bar");
    expect(preview()).toHaveTextContent("Created: bar_a");
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Run renamed",
        description: "2 experiments renamed: bar_a • bar_b",
      }),
    );
  });

  it("shows the old name again when the rename fails", async () => {
    patch.mockRejectedValue({ response: { data: { message: "Server down" } } });
    finishedRun("foo");
    renderName();

    commitName("bar");

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith(
        expect.objectContaining({
          title: "Couldn't rename the run",
          description: "Server down",
          variant: "destructive",
        }),
      ),
    );
    expect(usePlaygroundStore.getState().experimentName).toBe("foo");
    expect(editor()).toHaveTextContent("foo");
  });

  it("keeps tracking only the experiments that took the new name", async () => {
    patch.mockResolvedValueOnce({}).mockRejectedValueOnce({
      response: { data: { message: "Experiment not found" } },
    });
    finishedRun("foo");
    renderName();

    commitName("bar");

    await waitFor(() =>
      expect(toast).toHaveBeenCalledWith(
        expect.objectContaining({
          title: "Run partly renamed",
          description:
            "Renamed 1 of 2 experiments: bar_a. The others kept their old name. Experiment not found",
        }),
      ),
    );
    expect(usePlaygroundStore.getState().experimentName).toBe("bar");
    expect(usePlaygroundStore.getState().lastRun?.experiments).toEqual([
      { id: "e1", index: 0 },
    ]);
  });

  it("names an auto-named run's experiments", async () => {
    patch.mockResolvedValue({});
    finishedRun(null);
    renderName();

    expect(editor()).toHaveTextContent("Auto-generated name");

    commitName("bar");

    await waitFor(() => expect(patch).toHaveBeenCalledTimes(2));
    expect(patch).toHaveBeenCalledWith("/v1/private/experiments/e2", {
      name: "bar_b",
    });
  });

  it("shows the names the server gave an auto-named run", async () => {
    const serverNames: Record<string, string> = {
      "/v1/private/experiments/e1": "brave_tiger_1234",
      "/v1/private/experiments/e2": "calm_river_5678",
    };
    get.mockImplementation(async (url: string) => ({
      data: { id: url.split("/").pop(), name: serverNames[url] },
    }));
    patch.mockResolvedValue({});
    finishedRun(null);
    renderName();

    await waitFor(() => expect(editor()).toHaveTextContent("brave_tiger_1234"));
    expect(editor()).not.toHaveTextContent("Auto-generated name");
    expect(
      screen.getByTestId("playground-experiment-name-more"),
    ).toHaveTextContent("+1 more");

    commitName("bar", /brave_tiger_1234/);

    await waitFor(() =>
      expect(usePlaygroundStore.getState().experimentName).toBe("bar"),
    );
    expect(patch.mock.calls).toEqual([
      ["/v1/private/experiments/e1", { name: "bar_a" }],
      ["/v1/private/experiments/e2", { name: "bar_b" }],
    ]);
    expect(
      screen.queryByTestId("playground-experiment-name-more"),
    ).not.toBeInTheDocument();
  });

  it("clearing the box lets the next run be auto-named instead", () => {
    finishedRun("foo");
    renderName();

    commitName("");

    expect(patch).not.toHaveBeenCalled();
    expect(usePlaygroundStore.getState().lastRun).toBeNull();
    expect(screen.getByText("New Experiment:")).toBeInTheDocument();
  });

  it("names the next run when the last run was on another dataset", () => {
    finishedRun("foo");
    renderName("other-dataset");

    expect(screen.getByText("New Experiment:")).toBeInTheDocument();

    commitName("bar");

    expect(patch).not.toHaveBeenCalled();
    expect(usePlaygroundStore.getState().experimentName).toBe("bar");
    expect(preview()).toHaveTextContent("Creates: bar_a");
  });
});
