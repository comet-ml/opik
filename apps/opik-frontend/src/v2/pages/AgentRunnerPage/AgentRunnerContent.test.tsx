import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import AgentRunnerContent from "./AgentRunnerContent";
import {
  RunnerConnectionStatus,
  SandboxJobStatus,
} from "@/types/agent-sandbox";
import useSandboxJobStatus from "@/api/agent-sandbox/useSandboxJobStatus";
import useSandboxCreateJobMutation from "@/api/agent-sandbox/useSandboxCreateJobMutation";
import type { PairingState } from "@/hooks/usePairingState";
import { ReactNode } from "react";
import { TooltipProvider } from "@/ui/tooltip";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";

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

const mockUseSandboxJobStatus = vi.mocked(useSandboxJobStatus);
const mockUseSandboxCreateJobMutation = vi.mocked(useSandboxCreateJobMutation);

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

interface NavigationBlockerOptions {
  condition: boolean;
  title: string;
  description: string;
  confirmText: string;
  cancelText: string;
}

const mockUseNavigationBlocker = vi.fn();

vi.mock("@/hooks/useNavigationBlocker", () => ({
  default: (options: NavigationBlockerOptions) =>
    mockUseNavigationBlocker(options),
}));

describe("AgentRunnerContent", () => {
  let queryClient: QueryClient;

  const setConnectedRunner = () => {
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
  };

  const createWrapper = (qc: QueryClient) => {
    const Wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={qc}>
        <PermissionsProvider value={DEFAULT_PERMISSIONS}>
          <TooltipProvider>{children}</TooltipProvider>
        </PermissionsProvider>
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
    mockUseNavigationBlocker.mockReturnValue({
      DialogComponent: <div data-testid="navigation-blocker-dialog" />,
    });
    mockUseSandboxJobStatus.mockReturnValue({
      data: null,
    } as unknown as ReturnType<typeof useSandboxJobStatus>);
    mockUseSandboxCreateJobMutation.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as unknown as ReturnType<typeof useSandboxCreateJobMutation>);
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

  it("calls useNavigationBlocker with the agent-playground copy and a falsy condition when no job is active", () => {
    setConnectedRunner();

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(mockUseNavigationBlocker).toHaveBeenCalledWith({
      condition: false,
      title: "Agent execution in progress",
      description:
        "Your agent is currently running. Leaving now will interrupt the execution and may result in an incomplete trace. Are you sure you want to leave?",
      confirmText: "Leave anyway",
      cancelText: "Stay and wait",
    });
  });

  it("blocks navigation while the sandbox job is RUNNING", () => {
    setConnectedRunner();
    mockUseSandboxJobStatus.mockReturnValue({
      data: { status: SandboxJobStatus.RUNNING },
    } as unknown as ReturnType<typeof useSandboxJobStatus>);

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(mockUseNavigationBlocker).toHaveBeenCalledWith(
      expect.objectContaining({ condition: true }),
    );
  });

  it("blocks navigation while the sandbox job is PENDING", () => {
    setConnectedRunner();
    mockUseSandboxJobStatus.mockReturnValue({
      data: { status: SandboxJobStatus.PENDING },
    } as unknown as ReturnType<typeof useSandboxJobStatus>);

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(mockUseNavigationBlocker).toHaveBeenCalledWith(
      expect.objectContaining({ condition: true }),
    );
  });

  it("blocks navigation while the create-job mutation is in flight (before jobData arrives)", () => {
    setConnectedRunner();
    mockUseSandboxCreateJobMutation.mockReturnValue({
      mutate: vi.fn(),
      isPending: true,
    } as unknown as ReturnType<typeof useSandboxCreateJobMutation>);

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(mockUseNavigationBlocker).toHaveBeenCalledWith(
      expect.objectContaining({ condition: true }),
    );
  });

  it("does not block navigation when the sandbox job has finished", () => {
    setConnectedRunner();
    mockUseSandboxJobStatus.mockReturnValue({
      data: { status: SandboxJobStatus.COMPLETED },
    } as unknown as ReturnType<typeof useSandboxJobStatus>);

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(mockUseNavigationBlocker).toHaveBeenCalledWith(
      expect.objectContaining({ condition: false }),
    );
  });

  it("renders the dialog returned by useNavigationBlocker", () => {
    setConnectedRunner();

    render(<AgentRunnerContent projectId="proj-1" />, {
      wrapper: createWrapper(queryClient),
    });

    expect(screen.getByTestId("navigation-blocker-dialog")).toBeInTheDocument();
  });
});
