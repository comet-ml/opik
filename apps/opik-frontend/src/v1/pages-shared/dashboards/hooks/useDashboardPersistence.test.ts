import { renderHook, act } from "@testing-library/react";
import { type Mock } from "vitest";

import { useDashboardPersistence } from "./useDashboardPersistence";
import {
  Dashboard,
  DashboardState,
  DASHBOARD_TYPE,
  DASHBOARD_SCOPE,
} from "@/types/dashboard";

import useDashboardById from "@/api/dashboards/useDashboardById";
import useDashboardUpdateMutation from "@/api/dashboards/useDashboardUpdateMutation";
import {
  loadLocal,
  saveLocal,
  clearLocal,
} from "@/lib/dashboard/dashboardLocalDb";
import { keepaliveSaveDashboard } from "@/api/dashboards/dashboardKeepaliveSave";
import { useDashboardStore } from "@/store/DashboardStore";
import { isDashboardChanged } from "@/lib/dashboard/utils";

vi.mock("@/api/dashboards/useDashboardById");
vi.mock("@/api/dashboards/useDashboardUpdateMutation");
vi.mock("@/lib/dashboard/dashboardLocalDb");
vi.mock("@/api/dashboards/dashboardKeepaliveSave");
vi.mock("@/store/DashboardStore", () => {
  const subscribe = vi.fn(() => vi.fn());
  const getState = vi.fn(() => ({
    lastModified: 0,
    getDashboard: () => ({
      version: 4,
      sections: [{ id: "s1", title: "Section", widgets: [], layout: [] }],
      lastModified: 1000,
    }),
  }));

  return {
    useDashboardStore: Object.assign(vi.fn(), { subscribe, getState }),
  };
});
vi.mock("@/lib/dashboard/utils");

const makeConfig = (overrides?: Partial<DashboardState>): DashboardState => ({
  version: 4,
  sections: [{ id: "s1", title: "Section", widgets: [], layout: [] }],
  lastModified: 1000,
  ...overrides,
});

const makeDashboard = (overrides?: Partial<Dashboard>): Dashboard => ({
  id: "dash-1",
  name: "Test Dashboard",
  description: "",
  workspace_id: "ws-1",
  config: makeConfig(),
  type: DASHBOARD_TYPE.MULTI_PROJECT,
  scope: DASHBOARD_SCOPE.INSIGHTS,
  created_at: "2025-01-01T00:00:00Z",
  last_updated_at: "2025-01-01T00:00:00Z",
  ...overrides,
});

let syncToServerMutate: Mock;
let autoSaveMutate: Mock;
let storeListener:
  | ((state: {
      lastModified: number;
      getDashboard: () => DashboardState;
    }) => void)
  | null;
let storeUnsubscribe: Mock;

const setupMocks = (dashboard?: Dashboard) => {
  syncToServerMutate = vi.fn();
  autoSaveMutate = vi.fn();
  storeListener = null;
  storeUnsubscribe = vi.fn();

  vi.mocked(useDashboardById).mockReturnValue({
    data: dashboard,
    isPending: !dashboard,
  } as ReturnType<typeof useDashboardById>);

  let callCount = 0;
  vi.mocked(useDashboardUpdateMutation).mockImplementation(() => {
    callCount++;
    if (callCount % 2 === 1) {
      return { mutate: syncToServerMutate } as unknown as ReturnType<
        typeof useDashboardUpdateMutation
      >;
    }
    return { mutate: autoSaveMutate } as unknown as ReturnType<
      typeof useDashboardUpdateMutation
    >;
  });

  vi.mocked(loadLocal).mockResolvedValue(undefined);
  vi.mocked(saveLocal).mockResolvedValue(undefined);
  vi.mocked(clearLocal).mockResolvedValue(undefined);
  vi.mocked(keepaliveSaveDashboard).mockImplementation(() => {});
  vi.mocked(isDashboardChanged).mockReturnValue(false);

  const mockSubscribe = vi.fn((listener: typeof storeListener) => {
    storeListener = listener;
    return storeUnsubscribe;
  });

  const mockGetState = vi.fn(() => ({
    lastModified: 0,
    getDashboard: () => makeConfig(),
  }));

  vi.mocked(useDashboardStore).subscribe = mockSubscribe as never;
  vi.mocked(useDashboardStore).getState = mockGetState as never;
};

const flushPromises = () => act(async () => {});

describe("useDashboardPersistence", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    setupMocks(makeDashboard());
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  describe("config resolution", () => {
    it("resolves to backend config when no local entry exists", async () => {
      const dashboard = makeDashboard();
      setupMocks(dashboard);

      const { result } = renderHook(() =>
        useDashboardPersistence({ dashboardId: "dash-1" }),
      );

      await flushPromises();

      expect(result.current.resolvedConfig).toBe(dashboard.config);
      expect(syncToServerMutate).not.toHaveBeenCalled();
      expect(clearLocal).not.toHaveBeenCalled();
    });

    it("resolves to local config and syncs when local is newer and changed", async () => {
      const dashboard = makeDashboard();
      const localConfig = makeConfig({ lastModified: 9999 });
      setupMocks(dashboard);

      vi.mocked(loadLocal).mockResolvedValue({
        dashboardId: "dash-1",
        config: localConfig,
        savedAt: new Date("2025-06-01T00:00:00Z").getTime(),
      });
      vi.mocked(isDashboardChanged).mockReturnValue(true);

      const { result } = renderHook(() =>
        useDashboardPersistence({ dashboardId: "dash-1" }),
      );

      await flushPromises();

      expect(result.current.resolvedConfig).toBe(localConfig);
      expect(syncToServerMutate).toHaveBeenCalledWith(
        { dashboard: { id: "dash-1", config: localConfig } },
        expect.objectContaining({ onSettled: expect.any(Function) }),
      );

      const { onSettled } = syncToServerMutate.mock.calls[0][1];
      await act(async () => {
        onSettled();
      });
      expect(clearLocal).toHaveBeenCalledWith("dash-1");
    });

    it("resolves to backend config when local exists but content is identical", async () => {
      const dashboard = makeDashboard();
      setupMocks(dashboard);

      vi.mocked(loadLocal).mockResolvedValue({
        dashboardId: "dash-1",
        config: dashboard.config,
        savedAt: new Date("2025-06-01T00:00:00Z").getTime(),
      });
      vi.mocked(isDashboardChanged).mockReturnValue(false);

      const { result } = renderHook(() =>
        useDashboardPersistence({ dashboardId: "dash-1" }),
      );

      await flushPromises();

      expect(result.current.resolvedConfig).toBe(dashboard.config);
      expect(syncToServerMutate).not.toHaveBeenCalled();
      expect(clearLocal).toHaveBeenCalledWith("dash-1");
    });

    it("resolves to backend config when local is older", async () => {
      const dashboard = makeDashboard();
      setupMocks(dashboard);

      vi.mocked(loadLocal).mockResolvedValue({
        dashboardId: "dash-1",
        config: makeConfig({ lastModified: 500 }),
        savedAt: new Date("2024-01-01T00:00:00Z").getTime(),
      });

      const { result } = renderHook(() =>
        useDashboardPersistence({ dashboardId: "dash-1" }),
      );

      await flushPromises();

      expect(result.current.resolvedConfig).toBe(dashboard.config);
      expect(syncToServerMutate).not.toHaveBeenCalled();
      expect(clearLocal).toHaveBeenCalledWith("dash-1");
    });
  });

  describe("auto-save subscription", () => {
    const setupAutoSave = async () => {
      const dashboard = makeDashboard();
      setupMocks(dashboard);

      const { result, unmount } = renderHook(() =>
        useDashboardPersistence({ dashboardId: "dash-1" }),
      );

      await flushPromises();

      expect(result.current.resolvedConfig).not.toBeNull();
      expect(storeListener).not.toBeNull();
      return { result, unmount };
    };

    it("captures initial store state as baseline without saving", async () => {
      await setupAutoSave();

      act(() => {
        storeListener!({
          lastModified: 1,
          getDashboard: () => makeConfig(),
        });
      });

      expect(saveLocal).not.toHaveBeenCalled();
      expect(autoSaveMutate).not.toHaveBeenCalled();
    });

    it("saves to local and server when store has real changes", async () => {
      await setupAutoSave();

      const changedConfig = makeConfig({ lastModified: 2000 });

      act(() => {
        storeListener!({
          lastModified: 1,
          getDashboard: () => makeConfig(),
        });
      });

      vi.mocked(isDashboardChanged).mockReturnValue(true);

      act(() => {
        storeListener!({
          lastModified: 2,
          getDashboard: () => changedConfig,
        });
      });

      expect(saveLocal).toHaveBeenCalledWith("dash-1", changedConfig);

      act(() => {
        vi.advanceTimersByTime(2000);
      });

      expect(autoSaveMutate).toHaveBeenCalledWith(
        { dashboard: { id: "dash-1", config: changedConfig } },
        expect.objectContaining({
          onSuccess: expect.any(Function),
          onError: expect.any(Function),
        }),
      );
    });

    it("debounces rapid changes into single save", async () => {
      await setupAutoSave();

      act(() => {
        storeListener!({
          lastModified: 1,
          getDashboard: () => makeConfig(),
        });
      });

      vi.mocked(isDashboardChanged).mockReturnValue(true);

      act(() => {
        storeListener!({
          lastModified: 2,
          getDashboard: () => makeConfig({ lastModified: 2000 }),
        });
      });

      act(() => {
        storeListener!({
          lastModified: 3,
          getDashboard: () => makeConfig({ lastModified: 3000 }),
        });
      });

      act(() => {
        vi.advanceTimersByTime(2000);
      });

      expect(autoSaveMutate).toHaveBeenCalledTimes(1);
    });

    it("flushes pending changes on effect cleanup", async () => {
      const { unmount } = await setupAutoSave();

      act(() => {
        storeListener!({
          lastModified: 1,
          getDashboard: () => makeConfig(),
        });
      });

      vi.mocked(isDashboardChanged).mockReturnValue(true);

      const changedConfig = makeConfig({ lastModified: 5000 });

      (useDashboardStore.getState as Mock).mockReturnValue({
        lastModified: 2,
        getDashboard: () => changedConfig,
      });

      act(() => {
        storeListener!({
          lastModified: 2,
          getDashboard: () => changedConfig,
        });
      });

      expect(autoSaveMutate).not.toHaveBeenCalled();

      unmount();

      expect(autoSaveMutate).toHaveBeenCalledWith(
        { dashboard: { id: "dash-1", config: changedConfig } },
        expect.anything(),
      );
    });
  });

  describe("save status lifecycle", () => {
    const triggerAutoSave = async () => {
      const dashboard = makeDashboard();
      setupMocks(dashboard);

      const { result } = renderHook(() =>
        useDashboardPersistence({ dashboardId: "dash-1" }),
      );

      await flushPromises();

      act(() => {
        storeListener!({
          lastModified: 1,
          getDashboard: () => makeConfig(),
        });
      });

      vi.mocked(isDashboardChanged).mockReturnValue(true);

      act(() => {
        storeListener!({
          lastModified: 2,
          getDashboard: () => makeConfig({ lastModified: 2000 }),
        });
      });

      act(() => {
        vi.advanceTimersByTime(2000);
      });

      return result;
    };

    it("transitions idle → saving → saved → idle on success", async () => {
      const result = await triggerAutoSave();

      expect(result.current.saveStatus).toBe("saving");

      const { onSuccess } = autoSaveMutate.mock.calls[0][1];
      act(() => {
        onSuccess();
      });

      expect(result.current.saveStatus).toBe("saved");

      act(() => {
        vi.advanceTimersByTime(3000);
      });

      expect(result.current.saveStatus).toBe("idle");
    });

    it("transitions to error on failure", async () => {
      const saveResult = await triggerAutoSave();

      expect(saveResult.current.saveStatus).toBe("saving");

      const { onError } = autoSaveMutate.mock.calls[0][1];
      act(() => {
        onError();
      });

      expect(saveResult.current.saveStatus).toBe("error");
    });
  });

  describe("beforeunload", () => {
    it("saves locally and via keepalive on beforeunload with unsaved changes", async () => {
      const dashboard = makeDashboard();
      setupMocks(dashboard);

      renderHook(() => useDashboardPersistence({ dashboardId: "dash-1" }));

      await flushPromises();

      act(() => {
        storeListener!({
          lastModified: 1,
          getDashboard: () => makeConfig(),
        });
      });

      const unsavedConfig = makeConfig({ lastModified: 9999 });
      vi.mocked(isDashboardChanged).mockReturnValue(true);
      (useDashboardStore.getState as Mock).mockReturnValue({
        lastModified: 2,
        getDashboard: () => unsavedConfig,
      });

      act(() => {
        window.dispatchEvent(new Event("beforeunload"));
      });

      expect(saveLocal).toHaveBeenCalledWith("dash-1", unsavedConfig);
      expect(keepaliveSaveDashboard).toHaveBeenCalledWith(
        "dash-1",
        unsavedConfig,
      );
    });
  });
});
