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

let testSuiteTotal = 1;
let isTestSuitePending = false;
let activeProjectId: string | null = "project-id";

// The page asks once for any experiment (empty state) and once for test-suite
// experiments only (default tab); the second call is the one with filters.
vi.mock("@/api/datasets/useExperimentsList", () => ({
  default: (params: { filters?: unknown[] }) => ({
    data:
      params.filters && isTestSuitePending
        ? undefined
        : { total: params.filters ? testSuiteTotal : 1 },
    isPending: Boolean(params.filters) && isTestSuitePending,
    isError: false,
  }),
}));

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => activeProjectId,
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
    localStorage.clear();
    testSuiteTotal = 1;
    isTestSuitePending = false;
    activeProjectId = "project-id";
  });

  it("hides the tabs while the default tab is still being chosen", () => {
    isTestSuitePending = true;
    renderPage();

    expect(screen.queryByRole("tab")).toBeNull();
    expect(screen.queryByTestId("experiments-tab")).toBeNull();
  });

  it("hides the tabs until the project is known, since the saved tab is per project", () => {
    activeProjectId = null;
    localStorage.setItem(
      "experiments-tab-project-id",
      JSON.stringify("datasets"),
    );
    renderPage();

    expect(screen.queryByRole("tab")).toBeNull();
    expect(screen.queryByTestId("experiments-tab")).toBeNull();
  });

  it("opens the Test suites tab by default when the project has test suite experiments", () => {
    renderPage();

    expect(screen.getByRole("tab", { name: "Test suites" })).toHaveAttribute(
      "data-state",
      "active",
    );
    expect(screen.getByTestId("experiments-tab")).toHaveTextContent(
      "evaluation_suite",
    );
  });

  it("opens the Datasets tab by default when the project has no test suite experiments", () => {
    testSuiteTotal = 0;
    renderPage();

    expect(screen.getByTestId("experiments-tab")).toHaveTextContent("dataset");
  });

  it("reopens the last tab used in the project when the URL has none", () => {
    localStorage.setItem(
      "experiments-tab-project-id",
      JSON.stringify("datasets"),
    );
    renderPage();

    expect(screen.getByTestId("experiments-tab")).toHaveTextContent("dataset");
  });

  it("restores the tab from the URL and remembers it for the project", () => {
    window.history.replaceState(null, "", "/?tab=datasets");
    renderPage();

    expect(screen.getByTestId("experiments-tab")).toHaveTextContent("dataset");
    expect(localStorage.getItem("experiments-tab-project-id")).toBe(
      JSON.stringify("datasets"),
    );
  });

  it("keeps the selected tab in the URL and drops the other tab's table params", () => {
    window.history.replaceState(
      null,
      "",
      `/?search=foo&page=3&size=25&groups=${encodeURIComponent("[]")}`,
    );
    const { rerender } = renderPage();

    fireEvent.mouseDown(screen.getByRole("tab", { name: "Datasets" }));
    // In the app the router re-renders on URL changes; the window adapter alone does not.
    rerender(makePage());

    expect(getParam("tab")).toBe("datasets");
    expect(getParam("search")).toBeNull();
    expect(getParam("page")).toBeNull();
    expect(getParam("size")).toBeNull();
    expect(getParam("groups")).toBeNull();
    expect(screen.getByTestId("experiments-tab")).toHaveTextContent("dataset");
    expect(localStorage.getItem("experiments-tab-project-id")).toBe(
      JSON.stringify("datasets"),
    );
  });
});
