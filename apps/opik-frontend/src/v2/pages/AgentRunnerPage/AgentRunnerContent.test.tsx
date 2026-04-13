import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AgentRunnerContent from "./AgentRunnerContent";
import { RunnerConnectionStatus } from "@/types/agent-sandbox";
import type { PairingState } from "@/hooks/usePairingState";
import { ReactNode } from "react";
import { TooltipProvider } from "@/ui/tooltip";

let mockPairingState: PairingState;

vi.mock("@/hooks/usePairingState", () => ({
  default: vi.fn(() => mockPairingState),
}));

vi.mock("@/api/agent-sandbox/useSandboxCreateJobMutation", () => ({
  default: vi.fn(() => ({
    mutate: vi.fn(),
    isPending: false,
  })),
}));

vi.mock("@/api/agent-sandbox/useSandboxJobStatus", () => ({
  default: vi.fn(() => ({
    data: null,
  })),
}));

vi.mock("./AgentRunnerEmptyState", () => {
  const MockEmptyState = () => <div data-testid="empty-state">Empty state</div>;
  MockEmptyState.displayName = "MockEmptyState";
  return { default: MockEmptyState };
});

vi.mock("./AgentRunnerConnectedState", () => ({
  default: () => <div data-testid="connected-state">Connected</div>,
}));

vi.mock("./AgentRunnerExecutionPanel", () => ({
  default: () => <div data-testid="execution-panel" />,
}));

vi.mock("./AgentRunnerResult", () => ({
  default: () => null,
}));

describe("AgentRunnerContent", () => {
  let queryClient: QueryClient;

  const createWrapper = (qc: QueryClient) => {
    const Wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={qc}>
        <TooltipProvider>{children}</TooltipProvider>
      </QueryClientProvider>
    );
    Wrapper.displayName = "TestWrapper";
    return Wrapper;
  };

  beforeEach(() => {
    vi.clearAllMocks();
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    mockPairingState = {
      projectId: "proj-1",
      status: RunnerConnectionStatus.IDLE,
      runnerId: null,
      runner: null,
    };
  });

  it("shows empty state when idle", () => {
    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(screen.getByTestId("empty-state")).toBeInTheDocument();
  });

  it("shows connected state when runner is connected", () => {
    mockPairingState = {
      ...mockPairingState,
      status: RunnerConnectionStatus.CONNECTED,
      runner: {
        id: "runner-1",
        project_id: "proj-1",
        status: RunnerConnectionStatus.CONNECTED,
        agents: [{ name: "test-agent" }],
      },
    };

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(screen.getByTestId("connected-state")).toBeInTheDocument();
  });

  it("shows empty state when disconnected", () => {
    mockPairingState = {
      ...mockPairingState,
      status: RunnerConnectionStatus.DISCONNECTED,
    };

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(screen.getByTestId("empty-state")).toBeInTheDocument();
  });
});
