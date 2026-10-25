import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";

import { TooltipProvider } from "@/ui/tooltip";
import TraceLogsView, {
  DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  TraceLogsViewConfig,
} from "./TraceLogsView";

vi.mock("@/api/traces/useTracesList", () => ({
  default: () => ({
    data: { content: [], total: 0 },
    isPending: false,
    isPlaceholderData: false,
    isFetching: false,
    refetch: vi.fn(),
  }),
}));

vi.mock("@/api/traces/useTracesFeedbackScoresNames", () => ({
  default: () => ({ data: { scores: [] }, isPending: false }),
}));

vi.mock("@/api/traces/useSpansFeedbackScoresNames", () => ({
  default: () => ({ data: { scores: [] }, isPending: false }),
}));

vi.mock("@/api/traces/useTracesStatistic", () => ({
  default: () => ({ data: undefined, refetch: vi.fn() }),
}));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => false,
}));

vi.mock("@/contexts/server-sync-provider", () => ({
  useTruncationEnabled: () => true,
}));

vi.mock("@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel", () => ({
  default: () => null,
}));

vi.mock(
  "@/v2/pages-shared/traces/ThreadDetailsPanel/ThreadDetailsPanel",
  () => ({
    default: () => null,
  }),
);

const CUSTOM_EMPTY_STATE_CONFIG: TraceLogsViewConfig = {
  ...DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  emptyStateTitle: "No logs yet",
  emptyStateDescription: "Traces will appear here once you run a prompt.",
};

const renderView = (viewConfig?: TraceLogsViewConfig) =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <QueryParamProvider adapter={WindowHistoryAdapter}>
        <TooltipProvider delayDuration={0}>
          <TraceLogsView projectId="project-1" viewConfig={viewConfig} />
        </TooltipProvider>
      </QueryParamProvider>
    </QueryClientProvider>,
  );

beforeEach(() => {
  localStorage.clear();
  window.history.replaceState({}, "", "/");
});

describe("TraceLogsView empty state", () => {
  it("should keep the generic text for hosts on the default config", () => {
    renderView(DEFAULT_TRACE_LOGS_VIEW_CONFIG);

    expect(screen.getByText("There are no traces yet")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Traces will appear here once your agent starts receiving requests.",
      ),
    ).toBeInTheDocument();
  });

  it("should show the text a host sets in its config", () => {
    renderView(CUSTOM_EMPTY_STATE_CONFIG);

    expect(screen.getByText("No logs yet")).toBeInTheDocument();
    expect(
      screen.getByText("Traces will appear here once you run a prompt."),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("There are no traces yet"),
    ).not.toBeInTheDocument();
  });
});
