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

// A deployment with a hosted server supplies its own routes, and only those can
// hand off to another app. Swapped in per test rather than per file.
const { plugin } = vi.hoisted(() => ({
  plugin: { McpInstallRoutes: null as unknown },
}));
vi.mock("@/store/PluginsStore", async (importOriginal) => {
  const actual = await importOriginal<{ default: unknown }>();
  return {
    ...actual,
    default: (selector: (state: Record<string, unknown>) => unknown) =>
      selector({ McpInstallRoutes: plugin.McpInstallRoutes }),
  };
});

import { TooltipProvider } from "@/ui/tooltip";
import McpHintPopover from "./McpHintPopover";
import {
  MCP_CONFIRMATION_HOLD_MS,
  MCP_COPIED,
  MCP_COPIED_FEEDBACK_MS,
  MCP_PROMPT_ACTION,
  MCP_PROMPT_PITCH,
  MCP_TILES_LABEL,
} from "./constants";
import { McpHintTarget } from "./types";

const target: McpHintTarget = {
  traceId: "01a0a497-12f6-73e2-bb3d-56f286348309",
  projectId: "p1",
  entityType: "trace",
};

const renderCard = (onConfirmationChange = vi.fn(), onDismiss = vi.fn()) => ({
  onConfirmationChange,
  onDismiss,
  ...render(
    <TooltipProvider>
      <McpHintPopover
        onAction={vi.fn()}
        onConfirmationChange={onConfirmationChange}
        onDismiss={onDismiss}
        target={target}
      />
    </TooltipProvider>,
  ),
});

describe("the hint card", () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  it("offers a setup command per client, which is the install route here", () => {
    // A local server is a stdio process holding an API key, so the CLI is what
    // installs it. The prompt beside them cannot answer for that key.
    renderCard();

    for (const client of ["claude-code", "cursor", "vscode", "codex"]) {
      expect(screen.getByTestId(`mcp-route-${client}`)).toBeInTheDocument();
    }
    expect(screen.getByText(MCP_TILES_LABEL)).toBeInTheDocument();
    expect(screen.getByText(MCP_PROMPT_PITCH)).toBeInTheDocument();
    expect(screen.getByText(MCP_PROMPT_ACTION)).toBeInTheDocument();
  });

  it("says a copy landed for two seconds, then offers itself again", () => {
    renderCard();
    fireEvent.click(screen.getByTestId("mcp-route-prompt"));

    // Not a button while it says so: a message with a hover effect reads as
    // something to press.
    const copied = screen.getByTestId("mcp-route-prompt-copied");
    expect(copied).toHaveTextContent(MCP_COPIED);
    expect(copied.tagName).toBe("SPAN");
    expect(screen.queryByTestId("mcp-route-prompt")).toBeNull();

    // The card is untouched: the routes and the docs link stay where they were.
    expect(screen.getByTestId("mcp-route-cursor")).toBeInTheDocument();
    expect(screen.getByText("Learn more")).toBeInTheDocument();

    act(() => void vi.advanceTimersByTime(MCP_COPIED_FEEDBACK_MS));
    expect(screen.getByText(MCP_PROMPT_ACTION)).toBeInTheDocument();
  });

  it("ticks the client tile that was copied, and only that one", () => {
    renderCard();
    const cursor = screen.getByTestId("mcp-route-cursor");
    fireEvent.click(cursor);

    expect(cursor.querySelector(".lucide-check")).toBeTruthy();
    expect(
      screen.getByTestId("mcp-route-codex").querySelector(".lucide-copy"),
    ).toBeTruthy();

    act(() => void vi.advanceTimersByTime(MCP_COPIED_FEEDBACK_MS));
    expect(cursor.querySelector(".lucide-copy")).toBeTruthy();
  });

  it("does not hold the card open for a copy", () => {
    // Nothing took the card over, so hover governs it as it did before.
    const { onConfirmationChange } = renderCard();
    fireEvent.click(screen.getByTestId("mcp-route-cursor"));

    expect(onConfirmationChange).not.toHaveBeenCalled();
  });
});

describe("the hint card with a hosted server", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const HostedRoutes = ({
      onRouteUsed,
    }: {
      onRouteUsed: (outcome: {
        kind: "opened";
        confirmation: string;
        snippet: string;
      }) => void;
    }) => (
      <button
        type="button"
        data-testid="mcp-route-vscode"
        onClick={() =>
          onRouteUsed({
            kind: "opened",
            confirmation: "Opening VS Code…",
            snippet: "Connect me to Opik MCP",
          })
        }
      >
        VS Code
      </button>
    );
    HostedRoutes.displayName = "HostedRoutes";
    plugin.McpInstallRoutes = HostedRoutes;
  });
  afterEach(() => {
    vi.useRealTimers();
    plugin.McpInstallRoutes = null;
  });

  it("holds the card while the layout settles, then lets it go", () => {
    // Replacing the routes makes the card shorter, which can slide it out from
    // under a pointer that has not moved. The hold survives that; it is not a
    // state the card stays in.
    const { onConfirmationChange, onDismiss } = renderCard();
    fireEvent.click(screen.getByTestId("mcp-route-vscode"));

    expect(screen.getByText("Opening VS Code…")).toBeInTheDocument();
    expect(onConfirmationChange).toHaveBeenLastCalledWith(true);
    expect(onDismiss).not.toHaveBeenCalled();

    act(() => void vi.advanceTimersByTime(MCP_CONFIRMATION_HOLD_MS));
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it("hands the card back to hover when someone is reading it", () => {
    const { onConfirmationChange, onDismiss, container } = renderCard();
    fireEvent.click(screen.getByTestId("mcp-route-vscode"));

    // `:hover` is the pointer's own state, which jsdom does not model, so
    // stand in for it wherever the card asks.
    for (const el of [container, ...container.querySelectorAll("*")]) {
      (el as HTMLElement).matches = ((selector: string) =>
        selector === ":hover") as HTMLElement["matches"];
    }
    act(() => void vi.advanceTimersByTime(MCP_CONFIRMATION_HOLD_MS));

    expect(onDismiss).not.toHaveBeenCalled();
    expect(onConfirmationChange).toHaveBeenLastCalledWith(false);
    expect(screen.getByText("Opening VS Code…")).toBeInTheDocument();
  });

  it("says the fallback copy landed, for the same two seconds", () => {
    renderCard();
    fireEvent.click(screen.getByTestId("mcp-route-vscode"));

    const button = screen.getByLabelText("Copy it");
    fireEvent.click(button);
    expect(button.querySelector(".lucide-check")).toBeTruthy();

    act(() => void vi.advanceTimersByTime(MCP_COPIED_FEEDBACK_MS));
    expect(button.querySelector(".lucide-copy")).toBeTruthy();
  });
});
