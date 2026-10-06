import React from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import dayjs from "dayjs";
import utc from "dayjs/plugin/utc";

const { mockGet, storage } = vi.hoisted(() => ({
  mockGet: vi.fn(),
  storage: {} as Record<string, unknown>,
}));
let timeRange: string | undefined;

vi.mock("@/api/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/api/api")>()),
  default: { get: mockGet },
}));

vi.mock("@/hooks/useQueryParamAndLocalStorageState", () => ({
  default: () => [timeRange, vi.fn()],
}));

vi.mock("use-query-params", () => ({
  StringParam: {},
  useQueryParam: () => [undefined, vi.fn()],
}));

vi.mock("use-local-storage-state", async () => ({
  default: (
    await import("@/testing/localStorageStateMock")
  ).createLocalStorageStateMock(storage),
}));

import { PRESET_DATE_RANGES } from "@/shared/DateRangeSelect";
import {
  DATE_RANGE_PRESET_ALLTIME,
  DATE_RANGE_PRESET_PAST_7_DAYS,
  DEFAULT_DATE_PRESET,
} from "@/v2/pages-shared/traces/MetricDateRangeSelect";
import { LOGS_TYPE } from "@/constants/traces";
import useLogsType from "./useLogsType";
import useLogsIntervalWindow from "./useLogsIntervalWindow";

dayjs.extend(utc);

const now = dayjs(PRESET_DATE_RANGES.past24hours.to)
  .startOf("day")
  .add(12, "hours")
  .add(30, "minutes");

const dateRangeConfig = {
  defaultValue: DEFAULT_DATE_PRESET,
  storageKeySuffix: "",
};

const createWrapper = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const Wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return Wrapper;
};

const renderLogsType = () =>
  renderHook(
    ({ projectId }: { projectId: string }) => {
      const intervalWindow = useLogsIntervalWindow(dateRangeConfig);
      return {
        intervalWindow,
        ...useLogsType({
          projectId,
          dateRangeConfig,
          intervalWindow,
        }),
      };
    },
    { initialProps: { projectId: "project-1" }, wrapper: createWrapper() },
  );

const threadStats = (value: number) => ({
  data: { stats: [{ name: "thread_count", type: "COUNT", value }] },
});

const probeParams = async () => {
  await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(1));
  return mockGet.mock.calls[0][1].params;
};

const liveCustomRange = `${now
  .subtract(4, "days")
  .format("YYYY-MM-DD")},${now.format("YYYY-MM-DD")}`;

const renderAnsweredLogsType = async (
  range: string = DATE_RANGE_PRESET_PAST_7_DAYS,
) => {
  timeRange = range;
  mockGet.mockResolvedValueOnce(threadStats(3));
  const rendered = renderLogsType();
  await waitFor(() =>
    expect(rendered.result.current.logsType).toBe(LOGS_TYPE.threads),
  );
  return rendered;
};

const flushRequests = () =>
  act(() => new Promise((resolve) => setTimeout(resolve, 0)));

describe("useLogsType", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(now.toDate());
    mockGet.mockReset();
    mockGet.mockResolvedValue({ data: { stats: [] } });
    for (const key of Object.keys(storage)) delete storage[key];
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("should probe a preset range from its start with no end, as the tabs do", async () => {
    timeRange = DATE_RANGE_PRESET_PAST_7_DAYS;

    renderLogsType();

    const params = await probeParams();
    expect(params).toMatchObject({
      from_time: now.utc().subtract(6, "days").startOf("day").format(),
    });
    expect(params).not.toHaveProperty("to_time");
  });

  it("should probe a custom range ending today with an explicit end at the current time", async () => {
    timeRange = liveCustomRange;

    renderLogsType();

    expect(await probeParams()).toMatchObject({
      from_time: now.utc().subtract(4, "days").startOf("day").format(),
      to_time: now.utc().format(),
    });
  });

  it("should probe a custom range with its own start and end", async () => {
    const from = now.subtract(10, "days").format("YYYY-MM-DD");
    const to = now.subtract(3, "days").format("YYYY-MM-DD");
    timeRange = `${from},${to}`;

    renderLogsType();

    expect(await probeParams()).toMatchObject({
      from_time: dayjs(from).utc().startOf("day").format(),
      to_time: dayjs(to).utc().endOf("day").format(),
    });
  });

  it("should probe the default window when all time is selected, as the tabs do", async () => {
    timeRange = DATE_RANGE_PRESET_ALLTIME;

    renderLogsType();

    const params = await probeParams();
    expect(params).toMatchObject({
      from_time: now.utc().subtract(29, "days").startOf("day").format(),
    });
    expect(params).not.toHaveProperty("to_time");
  });

  it("should probe its own window when no window is passed", async () => {
    timeRange = liveCustomRange;

    renderHook(() => useLogsType({ projectId: "project-1", dateRangeConfig }), {
      wrapper: createWrapper(),
    });

    expect(await probeParams()).toMatchObject({
      from_time: now.utc().subtract(4, "days").startOf("day").format(),
      to_time: now.utc().format(),
    });
  });

  it("should not probe again when the window moves, and keep the tab", async () => {
    const { result } = await renderAnsweredLogsType(liveCustomRange);
    vi.setSystemTime(now.add(30, "seconds").toDate());

    act(() => {
      result.current.intervalWindow.reanchorToNow();
    });
    await flushRequests();

    expect(result.current.intervalWindow.intervalEnd).toBe(
      now.add(30, "seconds").utc().format(),
    );
    expect(mockGet).toHaveBeenCalledTimes(1);
    expect(result.current.needsDefaultResolution).toBe(false);
    expect(result.current.logsType).toBe(LOGS_TYPE.threads);
  });

  it("should keep the probe in flight on its selection's window while the shared window moves", async () => {
    timeRange = liveCustomRange;
    mockGet.mockReturnValueOnce(new Promise(() => {}));
    const { result } = renderLogsType();
    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(1));
    vi.setSystemTime(now.add(30, "seconds").toDate());

    act(() => {
      result.current.intervalWindow.reanchorToNow();
    });
    await flushRequests();

    expect(mockGet).toHaveBeenCalledTimes(1);
    expect(mockGet.mock.calls[0][1].signal.aborted).toBe(false);
    expect(result.current.needsDefaultResolution).toBe(true);
  });

  it("should keep the first answer's tab when the range changes", async () => {
    const { result, rerender } = await renderAnsweredLogsType();

    timeRange = DEFAULT_DATE_PRESET;
    rerender({ projectId: "project-1" });
    await flushRequests();

    expect(mockGet).toHaveBeenCalledTimes(1);
    expect(result.current.needsDefaultResolution).toBe(false);
    expect(result.current.logsType).toBe(LOGS_TYPE.threads);
  });

  it("should resolve the default again for another project", async () => {
    const { result, rerender } = await renderAnsweredLogsType();

    rerender({ projectId: "project-2" });

    await waitFor(() => expect(result.current.logsType).toBe(LOGS_TYPE.traces));
    expect(mockGet).toHaveBeenCalledTimes(2);
    expect(mockGet.mock.calls[1][1].params.project_id).toBe("project-2");
  });

  it("should probe another project on the window as it stands, not the one pinned for the previous project", async () => {
    const { result, rerender } = await renderAnsweredLogsType(liveCustomRange);
    vi.setSystemTime(now.add(30, "seconds").toDate());
    act(() => {
      result.current.intervalWindow.reanchorToNow();
    });
    await flushRequests();

    rerender({ projectId: "project-2" });

    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(2));
    expect(mockGet.mock.calls[1][1].params).toMatchObject({
      project_id: "project-2",
      to_time: now.add(30, "seconds").utc().format(),
    });
  });

  it("should not probe when a logs type is already stored", () => {
    timeRange = DATE_RANGE_PRESET_PAST_7_DAYS;
    storage["project-logsType-project-1"] = LOGS_TYPE.traces;

    const { result } = renderLogsType();

    expect(mockGet).not.toHaveBeenCalled();
    expect(result.current.needsDefaultResolution).toBe(false);
    expect(result.current.logsType).toBe(LOGS_TYPE.traces);
  });
});
