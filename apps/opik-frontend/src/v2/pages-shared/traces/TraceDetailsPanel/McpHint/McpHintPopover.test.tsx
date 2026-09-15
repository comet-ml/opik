import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent, act } from "@testing-library/react";

vi.mock("clipboard-copy", () => ({ default: vi.fn() }));
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

import { TooltipProvider } from "@/ui/tooltip";
import McpHintPopover from "./McpHintPopover";
import { MCP_COPIED_DISMISS_MS } from "./constants";
import { McpHintTarget } from "./types";

const target: McpHintTarget = {
  traceId: "01a0a497-12f6-73e2-bb3d-56f286348309",
  projectId: "p1",
  entityType: "trace",
};

const renderCard = (onDone = vi.fn()) => {
  const result = render(
    <TooltipProvider>
      <McpHintPopover onAction={vi.fn()} onDone={onDone} target={target} />
    </TooltipProvider>,
  );
  return { onDone, ...result };
};

describe("the hint card without a hosted server", () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  it("offers a setup command per client, which is the install route here", () => {
    // A local server is a stdio process holding an API key, so the CLI is what
    // installs it. The prompt beside them cannot answer for that key.
    renderCard();

    for (const client of ["claude-code", "cursor", "vscode", "codex"]) {
      expect(screen.getByTestId(`mcp-route-${client}`)).toBeInTheDocument();
    }
    expect(screen.getByTestId("mcp-route-prompt")).toBeInTheDocument();
  });

  it("confirms a copy with a tick", () => {
    renderCard();
    fireEvent.click(screen.getByTestId("mcp-route-prompt"));

    expect(
      screen.getByText("Copied — paste it into your agent"),
    ).toBeInTheDocument();
  });

  it("shows the copied command back, on one line", () => {
    const { container } = renderCard();
    fireEvent.click(screen.getByTestId("mcp-route-cursor"));

    expect(
      screen.getByText("Copied — paste it in your terminal"),
    ).toBeInTheDocument();
    expect(container.querySelector("code")?.textContent).toBe(
      "uvx opik mcp configure --ai-client cursor",
    );
    expect(container.querySelector(".h-7")).toBeTruthy();
  });

  it("gets out of the way once the confirmation has been read", () => {
    const { onDone } = renderCard();
    fireEvent.click(screen.getByTestId("mcp-route-prompt"));

    expect(onDone).not.toHaveBeenCalled();
    act(() => void vi.advanceTimersByTime(MCP_COPIED_DISMISS_MS));
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it("goes back to the prompt instead of closing under a reading pointer", () => {
    const { onDone, container } = renderCard();
    fireEvent.click(screen.getByTestId("mcp-route-prompt"));

    const card = container.querySelector("[class*='w-[279px]']") as HTMLElement;
    // `:hover` is the pointer's own state, which jsdom does not model, so stand
    // in for it at the one place the card asks.
    card.matches = ((selector: string) =>
      selector === ":hover") as HTMLElement["matches"];

    act(() => void vi.advanceTimersByTime(MCP_COPIED_DISMISS_MS));

    expect(onDone).not.toHaveBeenCalled();
    expect(screen.getByTestId("mcp-route-prompt")).toBeInTheDocument();
  });
});
