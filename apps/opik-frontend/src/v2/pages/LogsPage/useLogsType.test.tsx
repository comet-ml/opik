import React from "react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
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
import useLogsType from "./useLogsType";

dayjs.extend(utc);

const now = dayjs(PRESET_DATE_RANGES.past24hours.to)
  .startOf("day")
  .add(12, "hours")
  .add(30, "minutes");

const renderLogsType = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );

  return renderHook(
    () =>
      useLogsType({
        projectId: "project-1",
        dateRangeConfig: {
          defaultValue: DEFAULT_DATE_PRESET,
          storageKeySuffix: "",
        },
      }),
    { wrapper },
  );
};

const probeParams = async () => {
  await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(1));
  return mockGet.mock.calls[0][1].params;
};

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

  it("should probe a preset range with an explicit end at the current time", async () => {
    timeRange = DATE_RANGE_PRESET_PAST_7_DAYS;

    renderLogsType();

    expect(await probeParams()).toMatchObject({
      from_time: now.utc().subtract(6, "days").startOf("day").format(),
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

    expect(await probeParams()).toMatchObject({
      from_time: now.utc().subtract(29, "days").startOf("day").format(),
      to_time: now.utc().format(),
    });
  });
});
