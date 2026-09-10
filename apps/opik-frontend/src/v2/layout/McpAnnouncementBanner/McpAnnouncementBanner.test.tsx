import React from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import McpAnnouncementBanner from "./McpAnnouncementBanner";
import {
  MCP_BANNER_CAMPAIGN_ID,
  MCP_BANNER_COPY,
  MCP_BANNER_SHOWN_SESSION_KEY,
} from "./constants";

// ── mutable state the mock factories read ──────────────────────────────────
const storage: Record<string, unknown> = {};
// ───────────────────────────────────────────────────────────────────────────

vi.mock("use-local-storage-state", async () => {
  const { useCallback, useState } = await import("react");
  return {
    default: function useLocalStorageStateMock(
      key: string,
      options?: { defaultValue?: unknown },
    ) {
      const [value, setValue] = useState(() =>
        key in storage ? storage[key] : options?.defaultValue,
      );
      const set = useCallback(
        (next: unknown) => {
          const resolved = typeof next === "function" ? next(value) : next;
          storage[key] = resolved;
          setValue(resolved);
        },
        [key, value],
      );
      return [value, set];
    },
  };
});

vi.mock("@/store/AppStore", () => ({
  useActiveWorkspaceName: () => "my-workspace",
}));

// The height wiring belongs to the layout, not to this seam.
vi.mock("@/hooks/useObserveResizeNode", () => ({
  useObserveResizeNode: () => ({ ref: vi.fn(), node: undefined }),
}));

vi.mock("@/lib/analytics/tracking", async () => {
  const actual = await vi.importActual<
    typeof import("@/lib/analytics/tracking")
  >("@/lib/analytics/tracking");
  return { ...actual, trackEvent: vi.fn() };
});

const renderBanner = () =>
  render(<McpAnnouncementBanner onChangeHeight={vi.fn()} />);

beforeEach(() => {
  for (const key of Object.keys(storage)) delete storage[key];
  window.sessionStorage.clear();
  vi.mocked(trackEvent).mockClear();
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date("2026-10-01T09:00:00Z"));
});

afterEach(() => {
  vi.useRealTimers();
});

describe("McpAnnouncementBanner", () => {
  it("announces MCP with a link to the docs", () => {
    renderBanner();

    expect(screen.getByText(MCP_BANNER_COPY)).toBeInTheDocument();

    const cta = screen.getByRole("link", { name: /learn more/i });
    expect(cta).toHaveAttribute(
      "href",
      expect.stringContaining("/docs/opik/mcp-server"),
    );
    expect(cta).toHaveAttribute("target", "_blank");
  });

  it("reports the impression once per session, across remounts", () => {
    renderBanner().unmount();
    renderBanner();

    const impressions = vi
      .mocked(trackEvent)
      .mock.calls.filter(([event]) => event === OpikEvent.MCP_BANNER_SHOWN);

    expect(impressions).toHaveLength(1);
    expect(impressions[0][1]).toEqual({
      workspace_name: "my-workspace",
      campaign_id: MCP_BANNER_CAMPAIGN_ID,
      copy_variant: "full",
    });
    expect(
      window.sessionStorage.getItem(MCP_BANNER_SHOWN_SESSION_KEY),
    ).not.toBeNull();
  });

  it("reports a click on the call to action", () => {
    renderBanner();

    fireEvent.click(screen.getByRole("link", { name: /learn more/i }));

    expect(trackEvent).toHaveBeenCalledWith(OpikEvent.MCP_BANNER_CTA_CLICKED, {
      workspace_name: "my-workspace",
      campaign_id: MCP_BANNER_CAMPAIGN_ID,
      copy_variant: "full",
    });
  });

  it("reports a dismissal and takes the bar off screen", () => {
    renderBanner();

    fireEvent.click(screen.getByRole("button", { name: /dismiss/i }));

    expect(trackEvent).toHaveBeenCalledWith(OpikEvent.MCP_BANNER_DISMISSED, {
      workspace_name: "my-workspace",
      campaign_id: MCP_BANNER_CAMPAIGN_ID,
      copy_variant: "full",
    });
    expect(screen.queryByText(MCP_BANNER_COPY)).not.toBeInTheDocument();
  });

  it("stays dismissed on the next visit, and reports no impression", () => {
    renderBanner();
    fireEvent.click(screen.getByRole("button", { name: /dismiss/i }));

    window.sessionStorage.clear();
    vi.mocked(trackEvent).mockClear();
    renderBanner();

    expect(screen.queryByText(MCP_BANNER_COPY)).not.toBeInTheDocument();
    expect(trackEvent).not.toHaveBeenCalledWith(
      OpikEvent.MCP_BANNER_SHOWN,
      expect.anything(),
    );
  });

  it("is exposed to assistive technology as a named region", () => {
    renderBanner();

    expect(screen.getByRole("region", { name: /opik mcp/i })).toBeVisible();
  });
});
