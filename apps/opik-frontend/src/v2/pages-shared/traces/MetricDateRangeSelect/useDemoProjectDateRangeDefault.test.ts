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
import { useDemoProjectDateRangeDefault } from "./useDemoProjectDateRangeDefault";

const mockProjectQuery = (
  data: { name: string } | undefined,
  isPending = false,
) =>
  vi.mocked(useProjectById).mockReturnValue({
    data,
    isPending,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any);

describe("useDemoProjectDateRangeDefault", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("should default the demo project to 24 hours so its charts bucket hourly", () => {
    mockProjectQuery({ name: DEMO_PROJECT_NAME });

    const { result } = renderHook(() =>
      useDemoProjectDateRangeDefault("project-1"),
    );

    expect(result.current.defaultValue).toBe(DATE_RANGE_PRESET_PAST_24_HOURS);
  });

  it("should leave other projects on the workspace-wide default", () => {
    mockProjectQuery({ name: "My Real Project" });

    const { result } = renderHook(() =>
      useDemoProjectDateRangeDefault("project-1"),
    );

    expect(result.current.defaultValue).toBe(DEFAULT_DATE_PRESET);
  });

  it("should hold the URL sync while the project name is still loading", () => {
    mockProjectQuery(undefined, true);

    const { result } = renderHook(() =>
      useDemoProjectDateRangeDefault("project-1"),
    );

    expect(result.current.initSyncReady).toBe(false);
    // Before the name lands it must not claim the demo default.
    expect(result.current.defaultValue).toBe(DEFAULT_DATE_PRESET);
  });

  it("should release the URL sync once the project has loaded", () => {
    mockProjectQuery({ name: DEMO_PROJECT_NAME });

    const { result } = renderHook(() =>
      useDemoProjectDateRangeDefault("project-1"),
    );

    expect(result.current.initSyncReady).toBe(true);
  });

  it("should treat a missing projectId as settled rather than blocking forever", () => {
    // A disabled query stays pending, so keying readiness off isPending alone would deadlock
    // the URL sync for any caller without a project.
    mockProjectQuery(undefined, true);

    const { result } = renderHook(() => useDemoProjectDateRangeDefault());

    expect(result.current.initSyncReady).toBe(true);
    expect(result.current.defaultValue).toBe(DEFAULT_DATE_PRESET);
  });

  it("should not query when there is no projectId", () => {
    mockProjectQuery(undefined, true);

    renderHook(() => useDemoProjectDateRangeDefault());

    expect(useProjectById).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ enabled: false }),
    );
  });

  // The stored range is sticky across projects and outranks any defaultValue, so without its own
  // storage slot the demo project would inherit whatever range was last picked on a real project
  // and the 24h default would never apply.
  describe("storageKeySuffix", () => {
    it("should give the demo project its own storage slot", () => {
      mockProjectQuery({ name: DEMO_PROJECT_NAME });

      const { result } = renderHook(() =>
        useDemoProjectDateRangeDefault("project-1"),
      );

      expect(result.current.storageKeySuffix).toBe(`-${DEMO_PROJECT_NAME}`);
    });

    it("should leave other projects on the shared storage slot", () => {
      mockProjectQuery({ name: "My Real Project" });

      const { result } = renderHook(() =>
        useDemoProjectDateRangeDefault("project-1"),
      );

      expect(result.current.storageKeySuffix).toBe("");
    });

    it("should not claim the demo slot before the project name resolves", () => {
      mockProjectQuery(undefined, true);

      const { result } = renderHook(() =>
        useDemoProjectDateRangeDefault("project-1"),
      );

      expect(result.current.storageKeySuffix).toBe("");
    });
  });
});
