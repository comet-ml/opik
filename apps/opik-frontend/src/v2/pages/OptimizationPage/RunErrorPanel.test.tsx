import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

import RunErrorPanel from "./RunErrorPanel";
import { Optimization } from "@/types/optimizations";

// Controllable stub for the studio-logs hook so each test can pick the
// happy-path (logs load) or the logs-failed-to-load branch.
type LogsHookResult = {
  data: { content: string; url: null; expiresAt: null } | undefined;
  dataUpdatedAt: number;
  isError: boolean;
  refetch: () => void;
};

const successfulLogs: LogsHookResult = {
  data: {
    // Raw traceback (low-level) plus the backend's curated "[System]" message.
    content: [
      "INFO run started",
      "ValueError: reference key not found",
      "[System] A metric couldn't be evaluated. Check the metric configuration and that its reference keys exist in the dataset.",
    ].join("\n"),
    url: null,
    expiresAt: null,
  },
  dataUpdatedAt: 0,
  isError: false,
  refetch: vi.fn(),
};

let logsHookResult: LogsHookResult = successfulLogs;

vi.mock("@/api/optimizations/useOptimizationStudioLogs", () => ({
  default: () => logsHookResult,
}));

const optimization = {
  id: "opt-1",
  status: "error",
} as unknown as Optimization;

describe("RunErrorPanel", () => {
  beforeEach(() => {
    logsHookResult = successfulLogs;
  });

  it("surfaces the backend's high-level reason (not the raw traceback) plus a View logs action", () => {
    render(<RunErrorPanel optimization={optimization} />);

    expect(screen.getByText("Optimization failed")).toBeInTheDocument();
    // High-level message shown; the raw "ValueError: ..." line is NOT surfaced here.
    expect(
      screen.getByText(
        "A metric couldn't be evaluated. Check the metric configuration and that its reference keys exist in the dataset.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/ValueError/)).not.toBeInTheDocument();
    expect(screen.getByText("View logs")).toBeInTheDocument();
  });

  it("shows the logs-failed fallback copy and a retry action when logs can't load", () => {
    logsHookResult = {
      data: undefined,
      dataUpdatedAt: 0,
      isError: true,
      refetch: vi.fn(),
    };

    render(<RunErrorPanel optimization={optimization} />);

    expect(screen.getByText("Optimization failed")).toBeInTheDocument();
    expect(
      screen.getByText(
        "This run failed, but we couldn't load its logs to show why. Retry loading them below, or check the metric and model, then run it again.",
      ),
    ).toBeInTheDocument();
    // No "View logs" (there is no log content), but a retry action is offered.
    expect(screen.queryByText("View logs")).not.toBeInTheDocument();
    expect(screen.getByText("Retry loading logs")).toBeInTheDocument();
  });
});
