import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

vi.mock("clipboard-copy", () => ({ default: vi.fn() }));

import { TooltipProvider } from "@/ui/tooltip";
import McpRouteConfirmation from "./McpRouteConfirmation";
import { MCP_DEEPLINK_FALLBACK_NOTE } from "./constants";
import { McpRouteOutcome } from "./types";

const renderConfirmation = (route: McpRouteOutcome, onRecopy = vi.fn()) => ({
  onRecopy,
  ...render(
    <TooltipProvider>
      <McpRouteConfirmation route={route} onRecopy={onRecopy} />
    </TooltipProvider>,
  ),
});

const tick = (container: HTMLElement) =>
  container.querySelector("svg.mt-0\\.5");

describe("the route confirmation", () => {
  it("ticks a copy, which is finished business", () => {
    const { container } = renderConfirmation({
      kind: "copied",
      confirmation: "Copied — paste it in your terminal",
      snippet: "codex mcp add opik-mcp --url https://example.com",
    });

    expect(tick(container)).toBeTruthy();
  });

  it("does not tick an opened deeplink, which is not confirmed", () => {
    const { container } = renderConfirmation({
      kind: "opened",
      confirmation: "Opening VS Code…",
      note: MCP_DEEPLINK_FALLBACK_NOTE,
      snippet: "Connect me to Opik MCP, then debug a failing trace.",
    });

    expect(tick(container)).toBeNull();
    expect(screen.getByText("Opening VS Code…")).toBeInTheDocument();
  });

  it("puts the fallback note above the snippet it introduces", () => {
    const { container } = renderConfirmation({
      kind: "opened",
      confirmation: "Opening VS Code…",
      note: MCP_DEEPLINK_FALLBACK_NOTE,
      snippet: "Connect me to Opik MCP",
    });

    const note = screen.getByText(MCP_DEEPLINK_FALLBACK_NOTE);
    const snippet = container.querySelector("code")!;

    expect(
      note.compareDocumentPosition(snippet) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(note.className).toContain("text-foreground");
  });

  it("reports a recopy, so the funnel sees it and the clock restarts", () => {
    const { onRecopy } = renderConfirmation({
      kind: "opened",
      confirmation: "Opening VS Code…",
      snippet: "Connect me to Opik MCP",
    });

    fireEvent.click(screen.getByLabelText("Copy it"));

    expect(onRecopy).toHaveBeenCalledTimes(1);
  });
});
