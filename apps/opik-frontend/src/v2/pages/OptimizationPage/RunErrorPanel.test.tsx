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
  it("surfaces the failure reason from the logs plus a View logs action", () => {
    render(<RunErrorPanel optimization={optimization} />);

    expect(screen.getByText("Optimization failed")).toBeInTheDocument();
    expect(
      screen.getByText("ValueError: reference key not found"),
    ).toBeInTheDocument();
    expect(screen.getByText("View logs")).toBeInTheDocument();
  });
});
