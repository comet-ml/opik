import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { DEMO_PROJECT_NAME } from "@/constants/shared";
import {
  DATE_RANGE_PRESET_PAST_24_HOURS,
  DEFAULT_DATE_PRESET,
} from "./constants";

vi.mock("@/api/projects/useProjectById", () => ({
  default: vi.fn(),
}));

import useProjectById from "@/api/projects/useProjectById";
import {
  resolveProjectDateRangeDefault,
  useProjectDateRangeDefault,
} from "./useProjectDateRangeDefault";

const mockProjectQuery = (
  data: { name: string } | undefined,
  isPending = false,
) =>
  vi.mocked(useProjectById).mockReturnValue({
    data,
    isPending,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any);

describe("resolveProjectDateRangeDefault", () => {
  it("should default the demo project to 24 hours so its charts bucket hourly", () => {
    const result = resolveProjectDateRangeDefault(DEMO_PROJECT_NAME, true);

    expect(result.defaultValue).toBe(DATE_RANGE_PRESET_PAST_24_HOURS);
  });

  it("should leave other projects on the workspace-wide default", () => {
    const result = resolveProjectDateRangeDefault("My Real Project", true);

    expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
  });

  it("should not claim the demo default before the name is known", () => {
    const result = resolveProjectDateRangeDefault(undefined, false);

    expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
    expect(result.initSyncReady).toBe(false);
  });

  it("should not mistake the raw project id for a project name", () => {
    // LogsPage's own `projectName` falls back to the id while loading; callers must pass
    // project?.name instead, and an id must never read as the demo project.
    const result = resolveProjectDateRangeDefault(
      "019feaba-9c9b-71c3-93f5-905be65789c5",
      true,
    );

    expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
    expect(result.storageKeySuffix).toBe("");
  });

  // The stored range is sticky across projects and outranks any defaultValue, so without its own
  // storage slot the demo project would inherit whatever range was last picked on a real project
  // and the 24h default would never apply.
  describe("storageKeySuffix", () => {
    it("should give the demo project its own storage slot, named after the project", () => {
      const result = resolveProjectDateRangeDefault(DEMO_PROJECT_NAME, true);

      expect(result.storageKeySuffix).toBe(`-${DEMO_PROJECT_NAME}`);
    });

    it("should leave other projects on the shared storage slot", () => {
      const result = resolveProjectDateRangeDefault("My Real Project", true);

      expect(result.storageKeySuffix).toBe("");
    });

    it("should not claim the demo slot before the name resolves", () => {
      const result = resolveProjectDateRangeDefault(undefined, false);

      expect(result.storageKeySuffix).toBe("");
    });
  });
});

describe("useProjectDateRangeDefault", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should resolve the demo default once the project has loaded", () => {
    mockProjectQuery({ name: DEMO_PROJECT_NAME });

    const { result } = renderHook(() =>
      useProjectDateRangeDefault("project-1"),
    );

    expect(result.current.defaultValue).toBe(DATE_RANGE_PRESET_PAST_24_HOURS);
    expect(result.current.initSyncReady).toBe(true);
  });

  it("should hold the URL sync while the project is still loading", () => {
    mockProjectQuery(undefined, true);

    const { result } = renderHook(() =>
      useProjectDateRangeDefault("project-1"),
    );

    expect(result.current.initSyncReady).toBe(false);
    expect(result.current.defaultValue).toBe(DEFAULT_DATE_PRESET);
  });

  it("should treat a missing projectId as settled rather than blocking forever", () => {
    // A disabled query stays pending, so keying readiness off isPending alone would deadlock
    // the URL sync for any caller without a project.
    mockProjectQuery(undefined, true);

    const { result } = renderHook(() => useProjectDateRangeDefault());

    expect(result.current.initSyncReady).toBe(true);
    expect(result.current.defaultValue).toBe(DEFAULT_DATE_PRESET);
  });

  it("should treat a failed lookup as settled and fall back to the workspace default", () => {
    // react-query reports a cached error as not-pending with no data. Falling back beats hanging
    // the URL sync forever on a project whose name we will never learn.
    mockProjectQuery(undefined, false);

    const { result } = renderHook(() =>
      useProjectDateRangeDefault("project-1"),
    );

    expect(result.current.initSyncReady).toBe(true);
    expect(result.current.defaultValue).toBe(DEFAULT_DATE_PRESET);
    expect(result.current.storageKeySuffix).toBe("");
  });

  it("should not query when there is no projectId", () => {
    mockProjectQuery(undefined, true);

    renderHook(() => useProjectDateRangeDefault());

    expect(useProjectById).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ enabled: false }),
    );
  });

  it("should not refetch a project the page already loaded", () => {
    // Pages that own this query opt out of refetch-on-mount; re-observing it must not undo that.
    mockProjectQuery({ name: DEMO_PROJECT_NAME });

    renderHook(() => useProjectDateRangeDefault("project-1"));

    expect(useProjectById).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ refetchOnMount: false }),
    );
  });
});
