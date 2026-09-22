import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

vi.mock("clipboard-copy", () => ({ default: () => Promise.resolve() }));
vi.mock("@/lib/analytics/tracking", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  trackEvent: vi.fn(),
}));
vi.mock("@/store/AppStore", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  useActiveWorkspaceName: () => "my-workspace",
}));
vi.mock("@/api/projects/useProjectById", () => ({
  default: () => ({ data: { name: "my-agent" } }),
}));

import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { TooltipProvider } from "@/ui/tooltip";
import McpHintButton from "./McpHintButton";
import { McpHintTarget } from "./types";

const target: McpHintTarget = {
  traceId: "01a0a497-12f6-73e2-bb3d-56f286348309",
  projectId: "p1",
  entityType: "trace",
};

const renderButton = () =>
  render(
    <TooltipProvider>
      <McpHintButton target={target} />
    </TooltipProvider>,
  );

const closedCalls = () =>
  vi
    .mocked(trackEvent)
    .mock.calls.filter(([event]) => event === OpikEvent.MCP_HINT_CLOSED);

describe("the hint button", () => {
  beforeEach(() => vi.mocked(trackEvent).mockClear());

  it("reports a close when the card goes away with its node", () => {
    // Switching node or closing the panel unmounts this. An open that never
    // closes leaves the funnel holding a card that looks still on screen.
    const { unmount } = renderButton();
    fireEvent.click(screen.getByTestId("mcp-hint-button"));

    expect(closedCalls()).toHaveLength(0);
    unmount();
    expect(closedCalls()).toHaveLength(1);
  });

  it("reports nothing when the card was never opened", () => {
    const { unmount } = renderButton();
    unmount();

    expect(closedCalls()).toHaveLength(0);
  });
});
