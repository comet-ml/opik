import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AgentRunnerContent from "./AgentRunnerContent";
import { SandboxConnectionStatus } from "@/types/agent-sandbox";
import { ReactNode } from "react";
import { TooltipProvider } from "@/ui/tooltip";

// Track removeQueries calls on the real QueryClient
let removeQueriesSpy: ReturnType<typeof vi.spyOn>;

// Mutable state for connection status
let mockRunnerData: { status: string; agents: { name: string }[] } | null =
  null;

const mockRefetchPairCode = vi.fn();

vi.mock("@/api/agent-sandbox/useSandboxPairCode", () => ({
  default: vi.fn(() => ({
    data: {
      pair_code: "ABC123",
      expires_in_seconds: 300,
      created_at: Date.now(),
    },
    isPending: false,
    refetch: mockRefetchPairCode,
  })),
}));

vi.mock("@/api/agent-sandbox/useSandboxConnectionStatus", () => ({
  default: vi.fn(() => ({
    data: mockRunnerData,
  })),
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

// Mock child components to keep tests focused
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
    mockRunnerData = null;
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    removeQueriesSpy = vi.spyOn(queryClient, "removeQueries");
  });

  it("shows empty state with pair code when disconnected", () => {
    mockRunnerData = null;

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(screen.getByTestId("empty-state")).toBeInTheDocument();
    expect(screen.getByText("Pair code: ABC123")).toBeInTheDocument();
  });

  it("shows connected state when runner is connected", () => {
    mockRunnerData = {
      status: SandboxConnectionStatus.CONNECTED,
      agents: [{ name: "test-agent" }],
    };

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(screen.getByTestId("connected-state")).toBeInTheDocument();
  });

  it("clears cached pair code when runner connects", async () => {
    mockRunnerData = {
      status: SandboxConnectionStatus.CONNECTED,
      agents: [{ name: "test-agent" }],
    };

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    await waitFor(() => {
      expect(removeQueriesSpy).toHaveBeenCalledWith({
        queryKey: ["agent-sandbox", "pair-code", { projectId: "proj-1" }],
      });
    });
  });

  it("does NOT clear pair code cache when disconnected", () => {
    mockRunnerData = null;

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(removeQueriesSpy).not.toHaveBeenCalled();
  });
});
