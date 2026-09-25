import React from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";

const issuesQuery = vi.fn();
const tracesQuery = vi.fn();

vi.mock("@/store/AppStore", () => ({
  useActiveWorkspaceName: () => "workspace-1",
}));
vi.mock("@/api/signals/useAgentInsightsIssuesList", () => ({
  default: (...args: unknown[]) => issuesQuery(...args),
}));
vi.mock("@/api/traces/useTracesList", () => ({
  default: (...args: unknown[]) => tracesQuery(...args),
}));
vi.mock("@tanstack/react-router", () => ({
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
}));

import DiagnosticsReadyBadge from "./DiagnosticsReadyBadge";

const AUTO_RUN_AT = Date.parse("2026-09-20T10:00:00.000Z");

const lastCall = (query: typeof issuesQuery) =>
  query.mock.calls[query.mock.calls.length - 1];

describe("DiagnosticsReadyBadge", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    issuesQuery.mockReset().mockReturnValue({ data: { total: 7 } });
    tracesQuery.mockReset().mockReturnValue({ data: { total: 847 } });
  });

  afterEach(() => vi.useRealTimers());

  it("fetches nothing until the popover opens", () => {
    render(<DiagnosticsReadyBadge projectId="p1" autoRunAt={AUTO_RUN_AT} />);

    expect(lastCall(issuesQuery)[1]).toEqual({ enabled: false });
    expect(lastCall(tracesQuery)[1]).toEqual({ enabled: false });
  });

  it("on hover, counts the open issues and the traces in the run's 7-day window", () => {
    render(<DiagnosticsReadyBadge projectId="p1" autoRunAt={AUTO_RUN_AT} />);

    fireEvent.pointerEnter(screen.getByText("Ready"));
    act(() => {
      vi.advanceTimersByTime(200);
    });

    expect(
      screen.getByText("Your first diagnostic is ready"),
    ).toBeInTheDocument();
    expect(lastCall(issuesQuery)).toEqual([
      expect.objectContaining({ projectId: "p1", status: "open" }),
      { enabled: true },
    ]);
    const [traceParams, traceOptions] = lastCall(tracesQuery);
    expect(traceOptions).toEqual({ enabled: true });
    expect(traceParams.filters.map((f: { value: string }) => f.value)).toEqual([
      "2026-09-13T10:00:00.000Z",
      "2026-09-20T10:00:00.000Z",
    ]);
  });
});
