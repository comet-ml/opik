import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import ConfigurationPage from "./ConfigurationPage";

const state = vi.hoisted(() => ({
  tab: "cost-center" as string | undefined,
  modelPricesEnabled: false,
  setTab: vi.fn(),
}));

vi.mock("use-query-params", () => ({
  StringParam: {},
  useQueryParam: () => [state.tab, state.setTab],
}));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => state.modelPricesEnabled,
}));

vi.mock("@/store/PluginsStore", () => ({
  default: (selector: (s: Record<string, unknown>) => unknown) =>
    selector({ CollaboratorsTabTrigger: null, CollaboratorsTab: null }),
}));

vi.mock(
  "@/v2/pages/ConfigurationPage/FeedbackDefinitionsTab/FeedbackDefinitionsTab",
  () => ({ default: () => <div>feedback definitions content</div> }),
);
vi.mock("@/v2/pages/ConfigurationPage/EnvironmentsTab/EnvironmentsTab", () => ({
  default: () => <div>environments content</div>,
}));
vi.mock("@/v2/pages/ConfigurationPage/AIProvidersTab/AIProvidersTab", () => ({
  default: () => <div>ai providers content</div>,
}));
vi.mock("./WorkspacePreferencesTab/WorkspacePreferencesTab", () => ({
  default: () => <div>workspace preferences content</div>,
}));
vi.mock("./CostCenterTab/CostCenterTab", () => ({
  default: () => <div>cost center content</div>,
}));

describe("ConfigurationPage", () => {
  beforeEach(() => {
    state.tab = "cost-center";
    state.modelPricesEnabled = false;
    state.setTab.mockReset();
  });

  it("shows the default tab for a cost-center link while model prices are off, without rewriting the link", () => {
    render(<ConfigurationPage />);

    expect(screen.getByText("feedback definitions content")).toBeTruthy();
    expect(screen.queryByText("cost center content")).toBeNull();
    expect(screen.queryByRole("tab", { name: /Cost center/ })).toBeNull();
    expect(state.setTab).not.toHaveBeenCalled();
  });

  it("opens the Cost center tab from the link once model prices are on", () => {
    state.modelPricesEnabled = true;

    render(<ConfigurationPage />);

    expect(screen.getByText("cost center content")).toBeTruthy();
    expect(screen.getByRole("tab", { name: /Cost center/ })).toBeTruthy();
  });

  it("keeps any other tab from the link while model prices are off", () => {
    state.tab = "ai-provider";

    render(<ConfigurationPage />);

    expect(screen.getByText("ai providers content")).toBeTruthy();
  });
});
