import { renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { createElement, type ReactNode } from "react";
import usePairingState from "./usePairingState";
import { RunnerConnectionStatus } from "@/types/agent-sandbox";

let mockRunnerData: { status: string; id: string } | null = null;

vi.mock("@/api/agent-sandbox/useSandboxConnectionStatus", () => ({
  default: vi.fn(() => ({
    data: mockRunnerData,
  })),
}));

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const Wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children);
  Wrapper.displayName = "TestWrapper";
  return Wrapper;
}

describe("usePairingState", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockRunnerData = null;
  });

  it("returns IDLE when no runner data exists", () => {
    const { result } = renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    expect(result.current.status).toBe(RunnerConnectionStatus.IDLE);
    expect(result.current.runnerId).toBeNull();
    expect(result.current.runner).toBeNull();
  });

  it("returns CONNECTED when runner is connected", () => {
    mockRunnerData = {
      status: RunnerConnectionStatus.CONNECTED,
      id: "runner-1",
    };

    const { result } = renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    expect(result.current.status).toBe(RunnerConnectionStatus.CONNECTED);
    expect(result.current.runnerId).toBe("runner-1");
  });

  it("returns DISCONNECTED when runner is disconnected", () => {
    mockRunnerData = {
      status: RunnerConnectionStatus.DISCONNECTED,
      id: "runner-1",
    };

    const { result } = renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    expect(result.current.status).toBe(RunnerConnectionStatus.DISCONNECTED);
    expect(result.current.runnerId).toBe("runner-1");
  });

  it("passes projectId through", () => {
    const { result } = renderHook(() => usePairingState("proj-42"), {
      wrapper: createWrapper(),
    });

    expect(result.current.projectId).toBe("proj-42");
  });
});
