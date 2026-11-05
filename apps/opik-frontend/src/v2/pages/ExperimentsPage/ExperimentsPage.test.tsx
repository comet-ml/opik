import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";

import ExperimentsPage from "./ExperimentsPage";

vi.mock("./GeneralDatasetsTab/GeneralDatasetsTab", () => ({
  default: ({ evaluationMethod }: { evaluationMethod: string }) => (
    <div data-testid="experiments-tab">{evaluationMethod}</div>
  ),
}));

vi.mock("@/api/datasets/useExperimentsList", () => ({
  default: () => ({ data: { total: 1 }, isPending: false }),
}));

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => "project-id",
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({ permissions: { canCreateExperiments: false } }),
}));

vi.mock(
  "@/v2/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog",
  () => ({ default: () => null }),
);

const makePage = () => (
  <QueryParamProvider adapter={WindowHistoryAdapter}>
    <ExperimentsPage />
  </QueryParamProvider>
);

const renderPage = () => render(makePage());

const getParam = (key: string) =>
  new URLSearchParams(window.location.search).get(key);

describe("ExperimentsPage tabs", () => {
  beforeEach(() => {
    window.history.replaceState(null, "", "/");
  });

  it("opens the Test suites tab by default", () => {
    renderPage();

    expect(screen.getByRole("tab", { name: "Test suites" })).toHaveAttribute(
      "data-state",
      "active",
    );
    expect(screen.getByTestId("experiments-tab")).toHaveTextContent(
      "evaluation_suite",
    );
  });

  it("restores the tab from the URL", () => {
    window.history.replaceState(null, "", "/?tab=datasets");
    renderPage();

    expect(screen.getByTestId("experiments-tab")).toHaveTextContent("dataset");
  });

  it("keeps the selected tab in the URL and drops the other tab's table params", () => {
    window.history.replaceState(
      null,
      "",
      `/?search=foo&page=3&groups=${encodeURIComponent("[]")}`,
    );
    const { rerender } = renderPage();

    fireEvent.mouseDown(screen.getByRole("tab", { name: "Datasets" }));
    // In the app the router re-renders on URL changes; the window adapter alone does not.
    rerender(makePage());

    expect(getParam("tab")).toBe("datasets");
    expect(getParam("search")).toBeNull();
    expect(getParam("page")).toBeNull();
    expect(getParam("groups")).toBeNull();
    expect(screen.getByTestId("experiments-tab")).toHaveTextContent("dataset");
  });
});
