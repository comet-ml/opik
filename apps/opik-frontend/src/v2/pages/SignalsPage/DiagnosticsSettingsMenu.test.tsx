import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

const mutate = vi.fn();

vi.mock("@/api/signals/useUpdateAgentInsightsJobMutation", () => ({
  default: () => ({ mutate, isPending: false }),
}));

vi.mock("@/store/PluginsStore", () => ({
  default: () => null,
}));

vi.mock("@/lib/analytics/tracking", () => ({
  OpikEvent: {},
  trackEvent: vi.fn(),
}));

import DiagnosticsSettingsMenu from "./DiagnosticsSettingsMenu";

const openMenu = () =>
  fireEvent.keyDown(
    screen.getByRole("button", { name: "Diagnostics settings" }),
    {
      key: "Enter",
    },
  );

describe("DiagnosticsSettingsMenu", () => {
  beforeEach(() => mutate.mockClear());

  it("turns automatic daily diagnostics on", () => {
    render(
      <DiagnosticsSettingsMenu projectId="p1" enabled={false} canConfigure />,
    );

    openMenu();
    const row = screen.getByRole("menuitemcheckbox", {
      name: /Automatic daily diagnostics/,
    });
    expect(row).toHaveAttribute("aria-checked", "false");
    fireEvent.click(row);

    expect(mutate).toHaveBeenCalledWith({ projectId: "p1", status: "enabled" });
  });

  it("opens the guidance sheet from Edit project guidance", () => {
    const onEditGuidance = vi.fn();
    render(
      <DiagnosticsSettingsMenu
        projectId="p1"
        enabled
        canConfigure
        onEditGuidance={onEditGuidance}
      />,
    );

    openMenu();
    fireEvent.click(
      screen.getByRole("menuitem", { name: "Edit project guidance" }),
    );

    expect(onEditGuidance).toHaveBeenCalledTimes(1);
  });

  it("hides Edit project guidance when the guidance toggle is off", () => {
    render(<DiagnosticsSettingsMenu projectId="p1" enabled canConfigure />);

    openMenu();

    expect(
      screen.queryByRole("menuitem", { name: "Edit project guidance" }),
    ).not.toBeInTheDocument();
  });

  it("shows only Edit project guidance without configure permission", () => {
    render(
      <DiagnosticsSettingsMenu
        projectId="p1"
        enabled
        canConfigure={false}
        onEditGuidance={vi.fn()}
      />,
    );

    openMenu();

    expect(
      screen.getByRole("menuitem", { name: "Edit project guidance" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("menuitemcheckbox", {
        name: /Automatic daily diagnostics/,
      }),
    ).not.toBeInTheDocument();
  });
});
