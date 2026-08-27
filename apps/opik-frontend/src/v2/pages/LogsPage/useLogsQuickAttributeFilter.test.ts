import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { Filter } from "@/types/filters";
import { LOGS_TYPE } from "@/constants/traces";
import { QuickFilterEntity } from "@/shared/filter-chips/QuickAttributeFilterContext";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

vi.mock("@/lib/analytics/tracking", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("@/lib/analytics/tracking")>();
  return { ...actual, trackEvent: vi.fn() };
});

const urlSetters: Record<string, ReturnType<typeof vi.fn>> = {};
const urlValues: Record<string, unknown> = {};
const setPinnedIds = vi.fn();

vi.mock("use-query-params", () => ({
  JsonParam: {},
  NumberParam: {},
  useQueryParam: vi.fn((key: string) => {
    urlSetters[key] = urlSetters[key] ?? vi.fn();
    return [urlValues[key], urlSetters[key]];
  }),
}));

vi.mock("use-local-storage-state", () => ({
  default: vi.fn(() => [undefined, setPinnedIds]),
}));

import useLogsQuickAttributeFilter from "./useLogsQuickAttributeFilter";

const TRACES_KEY = "traces_filters";
const SPANS_KEY = "spans_filters";

// Applies the pending updater the hook passed to the URL setter, so the test
// sees the filters that would actually land in the query string.
const writtenTo = (key: string): Filter[] => {
  const updater = urlSetters[key]?.mock.calls.at(-1)?.[0];
  if (typeof updater !== "function") return [];
  return updater(urlValues[key]) ?? [];
};

// `entity` is what the details panel resolved — the selected span, or the trace
// when no span is selected or the selected one is not loaded.
const setup = (
  logsType: LOGS_TYPE,
  entity: QuickFilterEntity,
  withLocalChips = true,
) => {
  const onLogsTypeChange = vi.fn();
  const applyValue = vi.fn();
  const pinChip = vi.fn();
  const { result } = renderHook(() =>
    useLogsQuickAttributeFilter({
      logsType,
      onLogsTypeChange,
      ...(withLocalChips ? { values: {}, applyValue, pinChip } : {}),
    }),
  );
  return {
    result: {
      get current() {
        return result.current(entity);
      },
    },
    factory: result,
    onLogsTypeChange,
    applyValue,
    pinChip,
  };
};

describe("useLogsQuickAttributeFilter", () => {
  beforeEach(() => {
    for (const key of Object.keys(urlSetters)) delete urlSetters[key];
    for (const key of Object.keys(urlValues)) delete urlValues[key];
    setPinnedIds.mockClear();
    vi.mocked(trackEvent).mockClear();
  });

  // Only the events named here; the hook also fires QUICK_FILTER_APPLIED.
  const eventsNamed = (name: string) =>
    vi.mocked(trackEvent).mock.calls.filter(([event]) => event === name);

  describe("the panel's entity picks the destination view", () => {
    it("threads + trace: writes the Traces view and moves the table there", () => {
      const { result, onLogsTypeChange, applyValue } = setup(
        LOGS_TYPE.threads,
        "trace",
        false,
      );

      act(() => result.current.filter("metadata", "git.branch", "main"));

      expect(writtenTo(TRACES_KEY)).toHaveLength(1);
      expect(writtenTo(SPANS_KEY)).toHaveLength(0);
      expect(onLogsTypeChange).toHaveBeenCalledWith(LOGS_TYPE.traces);
      expect(urlSetters.page).toHaveBeenCalledWith(1);
      expect(setPinnedIds).toHaveBeenCalled();
      expect(applyValue).not.toHaveBeenCalled();
    });

    it("threads + span: writes the Spans view and moves the table there", () => {
      const { result, onLogsTypeChange } = setup(
        LOGS_TYPE.threads,
        "span",
        false,
      );

      act(() => result.current.filter("metadata", "model", "opus"));

      expect(writtenTo(SPANS_KEY)).toHaveLength(1);
      expect(writtenTo(TRACES_KEY)).toHaveLength(0);
      expect(onLogsTypeChange).toHaveBeenCalledWith(LOGS_TYPE.spans);
    });

    it("traces + span: writes the Spans view, leaving the trace chips alone", () => {
      const { result, onLogsTypeChange, applyValue, pinChip } = setup(
        LOGS_TYPE.traces,
        "span",
      );
      act(() => result.current.filter("metadata", "model", "opus"));

      expect(writtenTo(SPANS_KEY)).toHaveLength(1);
      expect(onLogsTypeChange).toHaveBeenCalledWith(LOGS_TYPE.spans);
      expect(applyValue).not.toHaveBeenCalled();
      expect(pinChip).not.toHaveBeenCalled();
    });
  });

  describe("the destination is already on screen, so the filter stays put", () => {
    it("traces + trace: applies to the mounted chips and does not move", () => {
      const { result, onLogsTypeChange, applyValue, pinChip } = setup(
        LOGS_TYPE.traces,
        "trace",
      );

      act(() => result.current.filter("metadata", "git.branch", "main"));

      expect(applyValue).toHaveBeenCalled();
      expect(pinChip).toHaveBeenCalledWith("metadata");
      expect(onLogsTypeChange).not.toHaveBeenCalled();
      expect(writtenTo(TRACES_KEY)).toHaveLength(0);
    });

    it("spans + span: applies to the mounted chips and does not move", () => {
      const { result, onLogsTypeChange, applyValue } = setup(
        LOGS_TYPE.spans,
        "span",
      );

      act(() => result.current.filter("metadata", "model", "opus"));

      expect(applyValue).toHaveBeenCalled();
      expect(onLogsTypeChange).not.toHaveBeenCalled();
      expect(writtenTo(SPANS_KEY)).toHaveLength(0);
    });

    it("keeps span-only targets available on the spans tab", () => {
      const { result } = setup(LOGS_TYPE.spans, "span");
      expect(result.current.canFilter("metadata", "provider")).toBe(true);
      expect(result.current.canFilter("metadata", "providers[0]")).toBe(false);
    });
  });

  describe("writing into the destination's existing filters", () => {
    const existing: Filter = {
      id: "1",
      field: "metadata",
      type: "dictionary",
      operator: "contains",
      key: "env",
      value: "prod",
    } as Filter;

    it("appends without rewriting the rows already there", () => {
      const foreign = { ...existing, id: "2", field: "some_future_field" };
      urlValues[SPANS_KEY] = [existing, foreign];
      const { result } = setup(LOGS_TYPE.threads, "span", false);

      act(() => result.current.filter("metadata", "model", "opus"));

      const rows = writtenTo(SPANS_KEY);
      expect(rows).toHaveLength(3);
      expect(rows.slice(0, 2)).toEqual([existing, foreign]);
      expect(rows[2]).toMatchObject({ key: "model", value: "opus" });
    });

    it("skips holes a hand-edited query string left behind", () => {
      urlValues[SPANS_KEY] = [null, existing];
      const { result, onLogsTypeChange } = setup(
        LOGS_TYPE.threads,
        "span",
        false,
      );

      act(() => result.current.filter("metadata", "model", "opus"));

      const rows = writtenTo(SPANS_KEY);
      expect(rows).toHaveLength(2);
      expect(rows[0]).toEqual(existing);
      expect(rows[1]).toMatchObject({ key: "model", value: "opus" });
      expect(onLogsTypeChange).toHaveBeenCalledWith(LOGS_TYPE.spans);
    });

    it("does not append a row the destination already has", () => {
      urlValues[SPANS_KEY] = [existing];
      const { result, onLogsTypeChange } = setup(
        LOGS_TYPE.threads,
        "span",
        false,
      );

      act(() => result.current.filter("metadata", "env", "prod"));

      expect(writtenTo(SPANS_KEY)).toEqual([existing]);
      // The table still moves: the user asked to see that filtered view.
      expect(onLogsTypeChange).toHaveBeenCalledWith(LOGS_TYPE.spans);
    });
  });

  describe("the chip events follow the filter to its destination", () => {
    it("reports both events against the destination, not the tab on screen", () => {
      const { result } = setup(LOGS_TYPE.threads, "span", false);
      act(() => result.current.filter("metadata", "model", "opus"));
      // The mocked setter only records the updater, so run it once.
      writtenTo(SPANS_KEY);

      // Only the routing matters here. The payload's shape is `useFilterChips`'
      // to define, and both paths build it from the same helper.
      expect(eventsNamed(OpikEvent.FILTER_APPLIED)).toHaveLength(1);
      expect(eventsNamed(OpikEvent.FILTER_APPLIED)[0][1]).toMatchObject({
        filter_name: "metadata",
        table_id: "logs.spans",
      });
      expect(eventsNamed(OpikEvent.FILTER_PINNED)).toHaveLength(1);
      expect(eventsNamed(OpikEvent.FILTER_PINNED)[0][1]).toMatchObject({
        filter_name: "metadata",
        table_id: "logs.spans",
      });
    });

    it("stays quiet on FILTER_APPLIED when the destination already has the row", () => {
      urlValues[SPANS_KEY] = [
        {
          id: "1",
          field: "metadata",
          type: "dictionary",
          operator: "contains",
          key: "env",
          value: "prod",
        } as Filter,
      ];
      const { result } = setup(LOGS_TYPE.threads, "span", false);
      act(() => result.current.filter("metadata", "env", "prod"));
      writtenTo(SPANS_KEY);

      expect(eventsNamed(OpikEvent.FILTER_APPLIED)).toEqual([]);
      // The chip is still pinned: the user asked to see that filtered view.
      expect(eventsNamed(OpikEvent.FILTER_PINNED)).toHaveLength(1);
    });

    it("leaves the events to the mounted chips when nothing moves", () => {
      const { result } = setup(LOGS_TYPE.traces, "trace");
      act(() => result.current.filter("metadata", "git.branch", "main"));

      expect(eventsNamed(OpikEvent.FILTER_APPLIED)).toEqual([]);
      expect(eventsNamed(OpikEvent.FILTER_PINNED)).toEqual([]);
    });
  });
});
