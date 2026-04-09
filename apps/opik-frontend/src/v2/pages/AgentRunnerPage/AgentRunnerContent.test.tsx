import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AgentRunnerContent from "./AgentRunnerContent";
import { RunnerConnectionStatus } from "@/types/agent-sandbox";
import type { PairingState } from "@/hooks/usePairingState";
import { ReactNode } from "react";
import { TooltipProvider } from "@/ui/tooltip";

const mockRequestNewCode = vi.fn();

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
  const MockEmptyState = ({ pairCode }: { pairCode: string }) => (
    <div data-testid="empty-state">Pair code: {pairCode}</div>
  );
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
      pairCode: null,
      expiresAt: null,
      runnerId: null,
      runner: null,
      requestNewCode: mockRequestNewCode,
    };
  });

  it("shows empty state with pair code when pairing", () => {
    mockPairingState = {
      ...mockPairingState,
      status: RunnerConnectionStatus.PAIRING,
      pairCode: "ABC123",
      expiresAt: Date.now() + 300_000,
      runnerId: "runner-1",
    };

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(screen.getByTestId("empty-state")).toBeInTheDocument();
    expect(screen.getByText("Pair code: ABC123")).toBeInTheDocument();
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

  it("shows empty state when disconnected (no pair code)", () => {
    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(screen.getByTestId("empty-state")).toBeInTheDocument();
  });
});
