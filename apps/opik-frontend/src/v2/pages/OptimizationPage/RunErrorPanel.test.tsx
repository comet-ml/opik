import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import RunErrorPanel from "./RunErrorPanel";
import { Optimization } from "@/types/optimizations";

vi.mock("@/api/optimizations/useOptimizationStudioLogs", () => ({
  default: () => ({
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
  }),
}));

const optimization = {
  id: "opt-1",
  status: "error",
} as unknown as Optimization;

describe("RunErrorPanel", () => {
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
});
