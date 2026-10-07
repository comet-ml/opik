import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import { LOGS_TYPE, TRACE_DATA_TYPE } from "@/constants/traces";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { useLogsQuickAttributeFilter } from "./useLogsQuickAttributeFilter";

vi.mock("@/lib/analytics/tracking", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("@/lib/analytics/tracking")>();
  return { ...actual, trackEvent: vi.fn() };
});

const wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryParamProvider adapter={WindowHistoryAdapter}>
    {children}
  </QueryParamProvider>
);

const setUrl = (params: Record<string, string>) => {
  const search = new URLSearchParams(params).toString();
  window.history.replaceState({}, "", `/logs${search ? `?${search}` : ""}`);
};

const readFilters = (key: string) => {
  const raw = new URLSearchParams(window.location.search).get(key);
  return raw ? JSON.parse(raw) : undefined;
};

const readPinned = (tableId: string) =>
  JSON.parse(localStorage.getItem(`chips:pinnedConfig:${tableId}`) ?? "null");

const PROJECT_ID = "p1";

const readRemembered = (urlKey: string, projectId = PROJECT_ID) => {
  const raw = sessionStorage.getItem(`logs-filters:${projectId}:${urlKey}`);
  return raw ? JSON.parse(raw) : undefined;
};

const setup = (type: TRACE_DATA_TYPE) => {
  const onLogsTypeChange = vi.fn();
  const { result } = renderHook(
    () =>
      useLogsQuickAttributeFilter({
        type,
        projectId: PROJECT_ID,
        onLogsTypeChange,
      }),
    { wrapper },
  );
  return { result, onLogsTypeChange };
};

describe("useLogsQuickAttributeFilter", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    vi.mocked(trackEvent).mockClear();
    setUrl({});
  });

  describe("trace selected on the traces table", () => {
    beforeEach(() => setUrl({ trace: "t1" }));

    it("applies to traces_filters without switching tabs", async () => {
      const { result, onLogsTypeChange } = setup(TRACE_DATA_TYPE.traces);
      expect(result.current.hint).toBeUndefined();

      await act(async () => {
        result.current.filter("input", "query", "hello");
      });

      expect(readFilters("traces_filters")).toEqual([
        expect.objectContaining({
          field: "custom",
          key: "input.query",
          operator: "contains",
          value: "hello",
        }),
      ]);
      expect(readFilters("spans_filters")).toBeUndefined();
      expect(onLogsTypeChange).not.toHaveBeenCalled();
      expect(readPinned("logs.traces")).toContain("custom");
    });

    it("remembers the filters for this project under the traces key", async () => {
      const { result } = setup(TRACE_DATA_TYPE.traces);

      await act(async () => {
        result.current.filter("input", "query", "hello");
      });

      expect(readRemembered("traces_filters")).toEqual(
        readFilters("traces_filters"),
      );
      expect(readRemembered("traces_filters")).toHaveLength(1);
      expect(readRemembered("spans_filters")).toBeUndefined();
      expect(readRemembered("traces_filters", "other")).toBeUndefined();
    });

    it("hides span-only attributes", () => {
      const { result } = setup(TRACE_DATA_TYPE.traces);
      expect(result.current.canFilter("metadata", "provider")).toBe(false);
    });
  });

  describe("span selected on the traces table", () => {
    beforeEach(() => setUrl({ trace: "t1", span: "s1" }));

    it("resolves against the span and advertises the redirect", () => {
      const { result } = setup(TRACE_DATA_TYPE.traces);
      expect(result.current.canFilter("metadata", "provider")).toBe(true);
      expect(result.current.hint).toBe("Filter in Spans table");
    });

    it("writes spans_filters and switches to the spans tab", async () => {
      const { result, onLogsTypeChange } = setup(TRACE_DATA_TYPE.traces);

      await act(async () => {
        result.current.filter("input", "query", "hi");
      });

      expect(readFilters("spans_filters")).toEqual([
        expect.objectContaining({
          field: "custom",
          key: "input.query",
          value: "hi",
        }),
      ]);
      expect(readFilters("traces_filters")).toBeUndefined();
      expect(onLogsTypeChange).toHaveBeenCalledWith(LOGS_TYPE.spans);
      expect(readRemembered("spans_filters")).toEqual(
        readFilters("spans_filters"),
      );
      expect(readRemembered("traces_filters")).toBeUndefined();
      expect(readPinned("logs.spans")).toContain("custom");
      expect(trackEvent).toHaveBeenCalledWith(OpikEvent.QUICK_FILTER_APPLIED, {
        data_type: TRACE_DATA_TYPE.spans,
        source: "input",
        filter_name: "custom",
        operator: "contains",
        table_id: "logs.spans",
      });
    });

    it("keeps existing spans filters, skips duplicate rows, but still pins, tracks and switches on a repeat click", async () => {
      const existing = [
        {
          id: "x",
          field: "name",
          type: "string",
          operator: "=",
          value: "chat",
        },
      ];
      setUrl({
        trace: "t1",
        span: "s1",
        spans_filters: JSON.stringify(existing),
      });
      const { result, onLogsTypeChange } = setup(TRACE_DATA_TYPE.traces);

      await act(async () => {
        result.current.filter("input", "query", "hi");
      });
      await act(async () => {
        result.current.filter("input", "query", "hi");
      });

      const filters = readFilters("spans_filters");
      expect(filters).toHaveLength(2);
      expect(filters[0]).toEqual(existing[0]);
      expect(readRemembered("spans_filters")).toEqual(filters);
      expect(onLogsTypeChange).toHaveBeenCalledTimes(2);
      expect(onLogsTypeChange).toHaveBeenLastCalledWith(LOGS_TYPE.spans);
      expect(trackEvent).toHaveBeenCalledTimes(2);
      expect(readPinned("logs.spans")).toContain("custom");
    });
  });

  describe("span selected after a bare landing", () => {
    it("appends to the remembered spans filters when the param is absent", async () => {
      const remembered = [
        {
          id: "x",
          field: "name",
          type: "string",
          operator: "=",
          value: "chat",
        },
      ];
      sessionStorage.setItem(
        `logs-filters:${PROJECT_ID}:spans_filters`,
        JSON.stringify(remembered),
      );
      setUrl({ trace: "t1", span: "s1" });
      const { result } = setup(TRACE_DATA_TYPE.traces);

      await act(async () => {
        result.current.filter("input", "query", "hi");
      });

      const filters = readFilters("spans_filters");
      expect(filters).toHaveLength(2);
      expect(filters[0]).toEqual(remembered[0]);
      expect(readRemembered("spans_filters")).toEqual(filters);
    });
  });

  describe("trace selected on the spans table", () => {
    beforeEach(() => setUrl({ trace: "t1" }));

    it("writes traces_filters and switches to the traces tab", async () => {
      const { result, onLogsTypeChange } = setup(TRACE_DATA_TYPE.spans);
      expect(result.current.hint).toBe("Filter in Traces table");

      await act(async () => {
        result.current.filter("metadata", "env", "prod");
      });

      expect(readFilters("traces_filters")).toHaveLength(1);
      expect(readRemembered("traces_filters")).toEqual(
        readFilters("traces_filters"),
      );
      expect(onLogsTypeChange).toHaveBeenCalledWith(LOGS_TYPE.traces);
    });
  });

  it("is a no-op for non-filterable keys", async () => {
    setUrl({ trace: "t1" });
    const { result, onLogsTypeChange } = setup(TRACE_DATA_TYPE.traces);

    await act(async () => {
      result.current.filter("metadata", "providers[0]", "openai");
    });

    expect(readFilters("traces_filters")).toBeUndefined();
    expect(readRemembered("traces_filters")).toBeUndefined();
    expect(onLogsTypeChange).not.toHaveBeenCalled();
    expect(trackEvent).not.toHaveBeenCalled();
  });
});
