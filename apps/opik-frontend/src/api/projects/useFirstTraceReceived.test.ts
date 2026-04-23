import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import useFirstTraceReceived from "./useFirstTraceReceived";

vi.mock("@/api/api", () => ({
  default: { get: vi.fn() },
  PROJECTS_KEY: "projects",
  PROJECTS_REST_ENDPOINT: "/v1/private/projects",
}));

vi.mock("@/constants/shared", () => ({
  DEMO_PROJECT_NAME: "opik-demo",
  INSTALL_OPIK_SKILLS_COMMAND: "",
}));

// Module-scope vars are safe here: vi.mock factories are called lazily,
// after these bindings are initialized.
let mockQueryData: unknown = undefined;
let capturedRefetchInterval:
  | ((q: { state: { data: unknown } }) => number | false)
  | undefined;

vi.mock("@tanstack/react-query", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@tanstack/react-query")>();
  return {
    ...actual,
    useQuery: vi.fn((options: Parameters<typeof actual.useQuery>[0]) => {
      capturedRefetchInterval =
        options.refetchInterval as typeof capturedRefetchInterval;
      return { data: mockQueryData, isPending: false };
    }),
  };
});

const POLL_INTERVAL_MS = 5000;
const MAX_POLL_DURATION_MS = 5 * 60 * 1000;

const makeProject = (overrides: Record<string, unknown> = {}) => ({
  id: "proj-1",
  name: "my-project",
  last_updated_trace_at: undefined as string | undefined,
  ...overrides,
});

const makeResponse = (projects: ReturnType<typeof makeProject>[]) => ({
  content: projects,
  total: projects.length,
});

describe("useFirstTraceReceived", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockQueryData = undefined;
    capturedRefetchInterval = undefined;
  });

  describe("hasTraces / firstTraceProjectId", () => {
    it("returns hasTraces false and null id when no project has traces", () => {
      mockQueryData = makeResponse([makeProject()]);

      const { result } = renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws", enabled: true }),
      );

      expect(result.current.hasTraces).toBe(false);
      expect(result.current.firstTraceProjectId).toBeNull();
    });

    it("returns hasTraces true and the project id when a project has traces", () => {
      mockQueryData = makeResponse([
        makeProject({
          id: "proj-traced",
          last_updated_trace_at: "2024-01-01T00:00:00Z",
        }),
      ]);

      const { result } = renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws", enabled: true }),
      );

      expect(result.current.hasTraces).toBe(true);
      expect(result.current.firstTraceProjectId).toBe("proj-traced");
    });

    it("filters out the demo project even when it has traces", () => {
      mockQueryData = makeResponse([
        makeProject({
          name: "opik-demo",
          last_updated_trace_at: "2024-01-01T00:00:00Z",
        }),
      ]);

      const { result } = renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws", enabled: true }),
      );

      expect(result.current.hasTraces).toBe(false);
      expect(result.current.firstTraceProjectId).toBeNull();
    });

    it("returns the most recently traced project when multiple qualify", () => {
      mockQueryData = makeResponse([
        makeProject({
          id: "older",
          last_updated_trace_at: "2024-01-01T00:00:00Z",
        }),
        makeProject({
          id: "newer",
          last_updated_trace_at: "2024-06-01T00:00:00Z",
        }),
      ]);

      const { result } = renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws", enabled: true }),
      );

      expect(result.current.firstTraceProjectId).toBe("newer");
    });

    it("handles undefined data gracefully", () => {
      mockQueryData = undefined;

      const { result } = renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws", enabled: true }),
      );

      expect(result.current.hasTraces).toBe(false);
      expect(result.current.firstTraceProjectId).toBeNull();
    });
  });

  describe("refetchInterval callback", () => {
    it("is not set when poll is false", () => {
      mockQueryData = makeResponse([]);
      renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws", poll: false }),
      );
      expect(capturedRefetchInterval).toBeUndefined();
    });

    it("returns POLL_INTERVAL_MS when no trace found and timeout not exceeded", () => {
      mockQueryData = makeResponse([]);
      const nowSpy = vi.spyOn(Date, "now").mockReturnValue(0);

      renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws", poll: true }),
      );

      const result = capturedRefetchInterval!({
        state: { data: mockQueryData },
      });
      expect(result).toBe(POLL_INTERVAL_MS);
      nowSpy.mockRestore();
    });

    it("returns false immediately when a traced project is present in query data", () => {
      const dataWithTrace = makeResponse([
        makeProject({ last_updated_trace_at: "2024-01-01T00:00:00Z" }),
      ]);
      mockQueryData = dataWithTrace;

      renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws", poll: true }),
      );

      const result = capturedRefetchInterval!({
        state: { data: dataWithTrace },
      });
      expect(result).toBe(false);
    });

    it("sets pollExpired and returns false once MAX_POLL_DURATION_MS is exceeded", async () => {
      mockQueryData = makeResponse([]);
      const emptyData = mockQueryData;

      // First call sets pollStartRef.current = 0; second call exceeds the limit.
      const nowSpy = vi
        .spyOn(Date, "now")
        .mockReturnValueOnce(0)
        .mockReturnValue(MAX_POLL_DURATION_MS + 1);

      const { result } = renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws", poll: true }),
      );

      capturedRefetchInterval!({ state: { data: emptyData } });

      await act(async () => {
        capturedRefetchInterval!({ state: { data: emptyData } });
      });

      expect(result.current.pollExpired).toBe(true);
      nowSpy.mockRestore();
    });

    it("does not set pollExpired when elapsed time is exactly at the limit", () => {
      mockQueryData = makeResponse([]);
      const emptyData = mockQueryData;

      const nowSpy = vi
        .spyOn(Date, "now")
        .mockReturnValueOnce(0)
        .mockReturnValue(MAX_POLL_DURATION_MS);

      renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws", poll: true }),
      );

      capturedRefetchInterval!({ state: { data: emptyData } });
      const result = capturedRefetchInterval!({ state: { data: emptyData } });

      expect(result).toBe(POLL_INTERVAL_MS);
      nowSpy.mockRestore();
    });
  });

  describe("pollExpired initial state", () => {
    it("starts as false", () => {
      mockQueryData = makeResponse([]);

      const { result } = renderHook(() =>
        useFirstTraceReceived({ workspaceName: "ws" }),
      );

      expect(result.current.pollExpired).toBe(false);
    });
  });
});
