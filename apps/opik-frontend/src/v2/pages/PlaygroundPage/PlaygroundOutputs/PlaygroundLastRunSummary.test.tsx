import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";

import { TooltipProvider } from "@/ui/tooltip";
import usePlaygroundStore, { PlaygroundLastRun } from "@/store/PlaygroundStore";
import PlaygroundLastRunSummary from "./PlaygroundLastRunSummary";

const get = vi.fn();

vi.mock("@/api/api", () => ({
  default: { get: (...args: unknown[]) => get(...args) },
  EXPERIMENTS_REST_ENDPOINT: "/v1/private/experiments/",
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "my-ws" })),
  useActiveProjectId: () => "project-1",
}));

const DATASET_ID = "dataset-1";
const SERVER_NAMES: Record<string, string> = {
  "/v1/private/experiments/e1": "brave_tiger_1234",
  "/v1/private/experiments/e2": "calm_river_5678",
};

const setLastRun = (lastRun: PlaygroundLastRun) =>
  usePlaygroundStore.setState({ lastRun });

const twoExperimentRun = (name: string | null): PlaygroundLastRun => ({
  name,
  datasetId: DATASET_ID,
  experiments: [
    { id: "e2", index: 1 },
    { id: "e1", index: 0 },
  ],
});

const renderSummary = async () => {
  const rootRoute = createRootRoute({ component: Outlet });
  const playgroundRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/$workspaceName/projects/$projectId/playground",
    component: () => <PlaygroundLastRunSummary datasetId={DATASET_ID} />,
  });
  const compareRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
    component: () => null,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([playgroundRoute, compareRoute]),
    history: createMemoryHistory({
      initialEntries: ["/my-ws/projects/project-1/playground"],
    }),
  });

  await router.load();
  await act(async () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <TooltipProvider>
          <RouterProvider router={router} />
        </TooltipProvider>
      </QueryClientProvider>,
    );
  });
};

const compareHref = (ids: string[]) =>
  `/my-ws/projects/project-1/experiments/${DATASET_ID}/compare?experiments=${encodeURIComponent(
    JSON.stringify(ids),
  )}`;

const hrefOf = (text: string | RegExp) =>
  screen.getByRole("link", { name: text }).getAttribute("href");

describe("PlaygroundLastRunSummary", () => {
  beforeEach(() => {
    get.mockReset();
    get.mockImplementation(async (url: string) => ({
      data: { name: SERVER_NAMES[url] },
    }));
    usePlaygroundStore.setState({ lastRun: null });
  });

  it("links each experiment of a named run and a comparison of all of them", async () => {
    setLastRun(twoExperimentRun("foo"));
    await renderSummary();

    expect(
      await screen.findByTestId("playground-last-run-summary"),
    ).toHaveTextContent("Last run complete: foo");
    expect(hrefOf("foo_a")).toBe(compareHref(["e1"]));
    expect(hrefOf("foo_b")).toBe(compareHref(["e2"]));
    expect(hrefOf("Compare results")).toBe(compareHref(["e1", "e2"]));
    expect(get).not.toHaveBeenCalled();
  });

  it("links an auto-named run's experiments under the names the server gave them", async () => {
    setLastRun(twoExperimentRun(null));
    await renderSummary();

    expect(
      await screen.findByRole("link", { name: "brave_tiger_1234" }),
    ).toHaveAttribute("href", compareHref(["e1"]));
    expect(hrefOf("calm_river_5678")).toBe(compareHref(["e2"]));
    expect(screen.getByTestId("playground-last-run-summary")).toHaveTextContent(
      /^Last run complete/,
    );
  });

  it("keeps an experiment whose name cannot be read", async () => {
    get.mockImplementation(async (url: string) => {
      if (url.endsWith("/e2")) throw new Error("Network Error");
      return { data: { name: SERVER_NAMES[url] } };
    });
    setLastRun(twoExperimentRun(null));
    await renderSummary();

    expect(
      await screen.findByRole("link", { name: "brave_tiger_1234" }),
    ).toHaveAttribute("href", compareHref(["e1"]));
    expect(hrefOf("Prompt B experiment")).toBe(compareHref(["e2"]));
    expect(hrefOf("Compare results")).toBe(compareHref(["e1", "e2"]));
  });

  it("stays hidden for a run that made one experiment", async () => {
    setLastRun({
      name: "foo",
      datasetId: DATASET_ID,
      experiments: [{ id: "e1", index: 0 }],
    });
    await renderSummary();

    expect(
      screen.queryByTestId("playground-last-run-summary"),
    ).not.toBeInTheDocument();
  });

  it("stays hidden for a run on another dataset", async () => {
    setLastRun({ ...twoExperimentRun("foo"), datasetId: "other-dataset" });
    await renderSummary();

    expect(
      screen.queryByTestId("playground-last-run-summary"),
    ).not.toBeInTheDocument();
  });

  it("can be dismissed until the next run", async () => {
    setLastRun(twoExperimentRun("foo"));
    await renderSummary();

    fireEvent.click(
      await screen.findByRole("button", { name: "Dismiss last run summary" }),
    );
    expect(
      screen.queryByTestId("playground-last-run-summary"),
    ).not.toBeInTheDocument();

    act(() =>
      setLastRun({
        name: "foo_02",
        datasetId: DATASET_ID,
        experiments: [
          { id: "e3", index: 0 },
          { id: "e4", index: 1 },
        ],
      }),
    );
    expect(screen.getByTestId("playground-last-run-summary")).toHaveTextContent(
      "Last run complete: foo_02",
    );
  });
});
