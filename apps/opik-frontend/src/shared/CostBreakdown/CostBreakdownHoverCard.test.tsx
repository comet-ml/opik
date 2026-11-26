import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import CostBreakdownHoverCard from "./CostBreakdownHoverCard";

const { isEnabled } = vi.hoisted(() => ({ isEnabled: vi.fn() }));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => isEnabled(),
}));

vi.mock("./CostBreakdownContent", () => ({
  default: () => <div>breakdown card</div>,
}));

const span = {
  model: "gpt-4.1-mini",
  provider: "openai",
  total_estimated_cost: 0.0000114,
};

const renderChip = (cost = span.total_estimated_cost) =>
  render(
    <TooltipProvider>
      <CostBreakdownHoverCard
        target={{ kind: "span", span: { ...span, total_estimated_cost: cost } }}
        tooltip="Estimated cost $0.0000114"
      >
        <span>&lt;$0.01</span>
      </CostBreakdownHoverCard>
    </TooltipProvider>,
  );

const hoverChip = () =>
  fireEvent.pointerEnter(screen.getByText("<$0.01"), { pointerType: "mouse" });

describe("CostBreakdownHoverCard", () => {
  beforeEach(() => {
    isEnabled.mockReset();
  });

  it("opens the breakdown on hover when model prices are on", async () => {
    isEnabled.mockReturnValue(true);
    renderChip();

    hoverChip();

    await waitFor(() =>
      expect(screen.getByText("breakdown card")).toBeTruthy(),
    );
  });

  it("keeps today's tooltip and never opens a card when model prices are off", async () => {
    isEnabled.mockReturnValue(false);
    renderChip();

    hoverChip();
    await new Promise((resolve) => setTimeout(resolve, 600));

    expect(screen.queryByText("breakdown card")).toBeNull();
    expect(screen.getByText("<$0.01")).toBeTruthy();
  });

  it("has nothing to break down for a cost of 0", async () => {
    isEnabled.mockReturnValue(true);
    renderChip(0);

    hoverChip();
    await new Promise((resolve) => setTimeout(resolve, 600));

    expect(screen.queryByText("breakdown card")).toBeNull();
  });
});
