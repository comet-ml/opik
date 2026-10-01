import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  render as renderComponent,
  screen,
  fireEvent,
} from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";

const render = (ui: React.ReactElement) =>
  renderComponent(<TooltipProvider>{ui}</TooltipProvider>);

const mutate = vi.fn();

vi.mock("@/api/signals/useUpdateAgentInsightsJobMutation", () => ({
  default: () => ({ mutate, isPending: false }),
}));

vi.mock("@/lib/analytics/tracking", () => ({
  OpikEvent: {
    DIAGNOSTICS_AUTO_ENABLED: "enabled",
    DIAGNOSTICS_AUTO_DISABLED: "disabled",
  },
  trackEvent: vi.fn(),
}));

import AutoRunToggle from "./AutoRunToggle";

describe("AutoRunToggle", () => {
  beforeEach(() => mutate.mockClear());

  it("turns auto-run on when it is off", () => {
    render(
      <AutoRunToggle projectId="p1" enabled={false} canConfigure={true} />,
    );

    fireEvent.click(screen.getByRole("button", { name: /Auto-run off/ }));

    expect(mutate).toHaveBeenCalledWith({
      projectId: "p1",
      status: "enabled",
    });
  });

  it("turns auto-run off when it is on", () => {
    render(<AutoRunToggle projectId="p1" enabled={true} canConfigure={true} />);

    fireEvent.click(screen.getByRole("button", { name: /Auto-run on/ }));

    expect(mutate).toHaveBeenCalledWith({
      projectId: "p1",
      status: "disabled",
    });
  });

  it("stays a read-only label without configure permission", () => {
    render(
      <AutoRunToggle projectId="p1" enabled={false} canConfigure={false} />,
    );

    expect(screen.getByText(/Auto-run off/)).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
