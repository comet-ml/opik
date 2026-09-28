import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

const AUTO_RUN_AT = "2026-09-20T10:00:00.000Z";

let job: { last_scan_at?: string; auto_first_run_at?: string } | undefined;
let lastSeen: string | null;

vi.mock("@/store/AppStore", () => ({
  useActiveProjectId: () => "project-1",
}));
vi.mock("@/api/signals/useAgentInsightsJob", () => ({
  default: () => ({ data: job }),
}));
vi.mock("@/hooks/useDiagnosticsRunState", () => ({
  default: () => ({ isRunning: false }),
}));
vi.mock("@/hooks/useDiagnosticsSeen", () => ({
  default: () => ({ lastSeen }),
}));
vi.mock("@/v2/layout/SideBar/MenuItem/DiagnosticsReadyBadge", () => ({
  default: () => <span>Ready</span>,
}));

import DiagnosticsNavBadge from "./DiagnosticsNavBadge";

const dot = (container: HTMLElement) =>
  container.querySelector("span.rounded-full");

describe("DiagnosticsNavBadge", () => {
  beforeEach(() => {
    lastSeen = null;
    // The free run's report: it landed a few minutes after the run was claimed.
    job = {
      auto_first_run_at: AUTO_RUN_AT,
      last_scan_at: "2026-09-20T10:05:00.000Z",
    };
  });

  it("shows the Ready badge for the free run's unseen report", () => {
    const { container } = render(<DiagnosticsNavBadge collapsed={false} />);

    expect(screen.getByText("Ready")).toBeInTheDocument();
    expect(dot(container)).toBeNull();
  });

  it("pulses the dot for the free run's report on the collapsed rail", () => {
    const { container } = render(<DiagnosticsNavBadge collapsed={true} />);

    expect(screen.queryByText("Ready")).not.toBeInTheDocument();
    expect(dot(container)).toHaveClass("motion-safe:animate-beacon-pulse");
  });

  it("shows the plain dot for any other unseen report", () => {
    // A manual run long after the free one falls outside its window.
    job = {
      auto_first_run_at: AUTO_RUN_AT,
      last_scan_at: "2026-09-21T10:00:00.000Z",
    };
    const { container } = render(<DiagnosticsNavBadge collapsed={false} />);

    expect(screen.queryByText("Ready")).not.toBeInTheDocument();
    expect(dot(container)).not.toHaveClass("motion-safe:animate-beacon-pulse");
  });

  it("shows nothing once the report has been seen", () => {
    lastSeen = "2026-09-20T10:05:00.000Z";
    const { container } = render(<DiagnosticsNavBadge collapsed={false} />);

    expect(container).toBeEmptyDOMElement();
  });
});
