import { renderHook, act, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { createElement, type ReactNode } from "react";
import usePairingState from "./usePairingState";
import { RunnerConnectionStatus } from "@/types/agent-sandbox";

const mockRefetch = vi.fn().mockResolvedValue({ data: null });
let mockPairCodeData: {
  pair_code: string;
  runner_id: string;
  expires_in_seconds: number;
  created_at: number;
} | null = null;
let mockIsFetching = false;

let mockRunnerData: { status: string; id: string } | null = null;

vi.mock("@/api/agent-sandbox/useSandboxPairCode", () => ({
  default: vi.fn(() => ({
    data: mockPairCodeData,
    isFetching: mockIsFetching,
    refetch: mockRefetch,
  })),
}));

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
    mockPairCodeData = null;
    mockIsFetching = false;
    mockRunnerData = null;
  });

  it("returns IDLE when no data exists", () => {
    const { result } = renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    expect(result.current.status).toBe(RunnerConnectionStatus.IDLE);
    expect(result.current.pairCode).toBeNull();
  });

  it("returns LOADING when fetching first code", () => {
    mockIsFetching = true;

    const { result } = renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    expect(result.current.status).toBe(RunnerConnectionStatus.LOADING);
  });

  it("returns PAIRING with code when pair code data exists", () => {
    mockPairCodeData = {
      pair_code: "ABC123",
      runner_id: "runner-1",
      expires_in_seconds: 3600,
      created_at: Date.now(),
    };

    const { result } = renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    expect(result.current.status).toBe(RunnerConnectionStatus.PAIRING);
    expect(result.current.pairCode).toBe("ABC123");
    expect(result.current.runnerId).toBe("runner-1");
    expect(result.current.expiresAt).toBeGreaterThan(Date.now());
  });

  it("returns EXPIRED when code has timed out", () => {
    mockPairCodeData = {
      pair_code: "ABC123",
      runner_id: "runner-1",
      expires_in_seconds: 300,
      created_at: Date.now() - 400_000,
    };

    const { result } = renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    expect(result.current.status).toBe(RunnerConnectionStatus.EXPIRED);
    expect(result.current.pairCode).toBeNull();
  });

  it("CONNECTED takes priority over pair code data", () => {
    mockPairCodeData = {
      pair_code: "ABC123",
      runner_id: "runner-1",
      expires_in_seconds: 3600,
      created_at: Date.now(),
    };
    mockRunnerData = {
      status: RunnerConnectionStatus.CONNECTED,
      id: "runner-1",
    };

    const { result } = renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    expect(result.current.status).toBe(RunnerConnectionStatus.CONNECTED);
    expect(result.current.pairCode).toBeNull();
  });

  it("auto-requests code on mount when autoRequestOnMount is true", () => {
    const { result } = renderHook(
      () => usePairingState("proj-1", { autoRequestOnMount: true }),
      { wrapper: createWrapper() },
    );

    expect(mockRefetch).toHaveBeenCalled();
    expect(result.current.status).toBe(RunnerConnectionStatus.IDLE);
  });

  it("does not auto-request on mount by default", () => {
    renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    expect(mockRefetch).not.toHaveBeenCalled();
  });

  it("does not auto-request on mount when already connected", () => {
    mockRunnerData = {
      status: RunnerConnectionStatus.CONNECTED,
      id: "runner-1",
    };

    renderHook(() => usePairingState("proj-1", { autoRequestOnMount: true }), {
      wrapper: createWrapper(),
    });

    expect(mockRefetch).not.toHaveBeenCalled();
  });

  it("does not auto-request on mount when code already exists", () => {
    mockPairCodeData = {
      pair_code: "ABC123",
      runner_id: "runner-1",
      expires_in_seconds: 3600,
      created_at: Date.now(),
    };

    renderHook(() => usePairingState("proj-1", { autoRequestOnMount: true }), {
      wrapper: createWrapper(),
    });

    expect(mockRefetch).not.toHaveBeenCalled();
  });

  it("refetches on disconnect when autoRequestOnMount is true", async () => {
    mockRunnerData = {
      status: RunnerConnectionStatus.DISCONNECTED,
      id: "runner-1",
    };

    renderHook(() => usePairingState("proj-1", { autoRequestOnMount: true }), {
      wrapper: createWrapper(),
    });

    await waitFor(() => {
      expect(mockRefetch).toHaveBeenCalled();
    });
  });

  it("does not refetch on disconnect when autoRequestOnMount is false", () => {
    mockRunnerData = {
      status: RunnerConnectionStatus.DISCONNECTED,
      id: "runner-1",
    };

    renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    expect(mockRefetch).not.toHaveBeenCalled();
  });

  it("requestNewCode triggers refetch", () => {
    const { result } = renderHook(() => usePairingState("proj-1"), {
      wrapper: createWrapper(),
    });

    act(() => {
      result.current.requestNewCode();
    });

    expect(mockRefetch).toHaveBeenCalled();
  });
});
