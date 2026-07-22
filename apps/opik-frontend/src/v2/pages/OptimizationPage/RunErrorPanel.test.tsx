import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import RunErrorPanel from "./RunErrorPanel";
import { Optimization } from "@/types/optimizations";

vi.mock("@/api/optimizations/useOptimizationStudioLogs", () => ({
  default: () => ({
    data: {
      content: "INFO run started\nValueError: reference key not found",
      url: null,
      expiresAt: null,
    },
    dataUpdatedAt: 0,
  }),
}));

const optimization = {
  id: "opt-1",
  status: "error",
} as unknown as Optimization;

describe("RunErrorPanel", () => {
  it("falls back to the failure reason from the logs plus a View logs action", () => {
    render(<RunErrorPanel optimization={optimization} />);

    expect(screen.getByText("Optimization failed")).toBeInTheDocument();
    expect(
      screen.getByText("ValueError: reference key not found"),
    ).toBeInTheDocument();
    expect(screen.getByText("View logs")).toBeInTheDocument();
  });

  it("prefers the structured error_info.message over the scraped logs", () => {
    const optimizationWithErrorInfo = {
      id: "opt-1",
      status: "error",
      error_info: {
        exception_type: "InvalidMetricError",
        message: "invalid Python code in the metric",
        traceback: "Traceback (most recent call last): ...",
      },
    } as unknown as Optimization;

    render(<RunErrorPanel optimization={optimizationWithErrorInfo} />);

    expect(
      screen.getByText("invalid Python code in the metric"),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("ValueError: reference key not found"),
    ).not.toBeInTheDocument();
  });
});
