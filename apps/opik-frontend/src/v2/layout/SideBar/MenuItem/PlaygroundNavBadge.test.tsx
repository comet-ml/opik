import { describe, it, expect, vi, beforeEach } from "vitest";
import { render } from "@testing-library/react";

import PlaygroundNavBadge from "./PlaygroundNavBadge";
import { EXPERIMENT_STATUS } from "@/types/datasets";

let isOnPlayground: boolean;
let experimentByPromptId: Record<string, string>;
let hasUnseenRunCompletion: boolean;
let isRunInFlight: boolean;
let statuses: (EXPERIMENT_STATUS | undefined)[];
const setHasUnseenRunCompletion = vi.fn();
const settleRun = vi.fn();

vi.mock("@tanstack/react-router", () => ({
  useRouterState: ({ select }: { select: (s: unknown) => boolean }) =>
    select({
      location: {
        pathname: isOnPlayground ? "/p/1/playground" : "/p/1/traces",
      },
    }),
}));

// Module-scope spy: vi.mock factories run lazily, after this is initialized.
const useQueriesSpy = vi.fn(
  ({ queries }: { queries: { enabled?: boolean }[] }) =>
    queries.map((_, i) => ({ data: { status: statuses[i] } })),
);

vi.mock("@tanstack/react-query", () => ({
  useQueries: (args: { queries: { enabled?: boolean }[] }) =>
    useQueriesSpy(args),
}));

vi.mock("@/api/datasets/useExperimentById", () => ({
  getExperimentById: vi.fn(),
}));

vi.mock("@/store/PlaygroundStore", () => ({
  useExperimentByPromptId: () => experimentByPromptId,
  useHasUnseenRunCompletion: () => hasUnseenRunCompletion,
  useSetHasUnseenRunCompletion: () => setHasUnseenRunCompletion,
  useIsRunInFlight: () => isRunInFlight,
  useSettleRun: () => settleRun,
}));

const queriesEnabled = () =>
  useQueriesSpy.mock.calls.at(-1)?.[0].queries.some((q) => q.enabled) ?? false;

const renderBadge = () => render(<PlaygroundNavBadge collapsed={false} />);

describe("PlaygroundNavBadge", () => {
  beforeEach(() => {
    setHasUnseenRunCompletion.mockClear();
    settleRun.mockClear();
    useQueriesSpy.mockClear();
    isRunInFlight = true;
    isOnPlayground = false;
    experimentByPromptId = { "prompt-1": "exp-1" };
    hasUnseenRunCompletion = false;
    statuses = [EXPERIMENT_STATUS.RUNNING];
  });

  it("shows nothing while the run is still going", () => {
    const { container } = renderBadge();

    expect(container).toBeEmptyDOMElement();
    expect(setHasUnseenRunCompletion).not.toHaveBeenCalledWith(true);
  });

  // Experiment ids outlive the run that made them. Without the in-flight gate this polls for ever
  // on every page, on behalf of a run that finished sessions ago.
  it("does not poll when no run is in flight", () => {
    isRunInFlight = false;
    statuses = [EXPERIMENT_STATUS.COMPLETED];

    renderBadge();

    expect(queriesEnabled()).toBe(false);
    expect(setHasUnseenRunCompletion).not.toHaveBeenCalledWith(true);
  });

  it("does not poll while the playground is on screen", () => {
    isOnPlayground = true;

    renderBadge();

    expect(queriesEnabled()).toBe(false);
  });

  it("stops watching once the run it was following has finished", () => {
    statuses = [EXPERIMENT_STATUS.COMPLETED];

    renderBadge();

    expect(settleRun).toHaveBeenCalled();
  });

  it("flags a run that finished while the user was elsewhere", () => {
    const { rerender } = renderBadge();
    expect(setHasUnseenRunCompletion).not.toHaveBeenCalledWith(true);

    statuses = [EXPERIMENT_STATUS.COMPLETED];
    rerender(<PlaygroundNavBadge collapsed={false} />);

    expect(setHasUnseenRunCompletion).toHaveBeenCalledWith(true);
  });

  it("clears the flag once the playground is on screen", () => {
    isOnPlayground = true;
    hasUnseenRunCompletion = true;

    renderBadge();

    expect(setHasUnseenRunCompletion).toHaveBeenCalledWith(false);
  });

  it("renders the dot when there is a finished run to report", () => {
    hasUnseenRunCompletion = true;

    const { container } = renderBadge();

    expect(container).not.toBeEmptyDOMElement();
  });
});
