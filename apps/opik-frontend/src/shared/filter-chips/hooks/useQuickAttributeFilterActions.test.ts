import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import {
  resolveQuickFilterTarget,
  stringifyFilterValue,
  useQuickAttributeFilterActions,
} from "./useQuickAttributeFilterActions";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  ChipValueMap,
  QueryBuilderChipValue,
} from "@/shared/filter-chips/types";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

vi.mock("@/lib/analytics/tracking", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("@/lib/analytics/tracking")>();
  return { ...actual, trackEvent: vi.fn() };
});

const SPANS = TRACE_DATA_TYPE.spans;
const TRACES = TRACE_DATA_TYPE.traces;

describe("resolveQuickFilterTarget", () => {
  it("targets the metadata chip with the path as-is", () => {
    expect(resolveQuickFilterTarget("metadata", TRACES, "git.branch")).toEqual({
      chipId: "metadata",
      key: "git.branch",
    });
  });

  it("prefixes input/output paths and targets the custom chip", () => {
    expect(
      resolveQuickFilterTarget("input", SPANS, "messages[0].content"),
    ).toEqual({ chipId: "custom", key: "input.messages[0].content" });
  });

  it("routes a span's root provider to the dedicated provider field", () => {
    expect(resolveQuickFilterTarget("metadata", SPANS, "provider")).toEqual({
      chipId: "provider",
    });
  });

  it("does not offer provider filtering for traces, nor the providers aggregate", () => {
    expect(resolveQuickFilterTarget("metadata", TRACES, "provider")).toBeNull();
    expect(
      resolveQuickFilterTarget("metadata", TRACES, "providers"),
    ).toBeNull();
    expect(
      resolveQuickFilterTarget("metadata", SPANS, "providers[0]"),
    ).toBeNull();
  });
});

describe("stringifyFilterValue", () => {
  it("keeps strings, stringifies numbers/booleans, maps null to empty", () => {
    expect(stringifyFilterValue("main")).toBe("main");
    expect(stringifyFilterValue(0)).toBe("0");
    expect(stringifyFilterValue(true)).toBe("true");
    expect(stringifyFilterValue(null)).toBe("");
  });

  it("coerces array/object values via String() (only reached for scalars in practice)", () => {
    expect(stringifyFilterValue([1, 2] as never)).toBe("1,2");
    expect(stringifyFilterValue({} as never)).toBe("[object Object]");
  });
});

describe("useQuickAttributeFilterActions", () => {
  const TABLE_ID = "logs.test";
  const setup = (type = SPANS, values = {}) => {
    const applyValue = vi.fn();
    const pinChip = vi.fn();
    const { result } = renderHook(() =>
      useQuickAttributeFilterActions({
        type,
        tableId: TABLE_ID,
        values,
        applyValue,
        pinChip,
      }),
    );
    return { result, applyValue, pinChip };
  };

  beforeEach(() => vi.mocked(trackEvent).mockClear());

  describe("canFilter", () => {
    it("allows stored metadata keys and span provider, blocks computed keys", () => {
      const { result } = setup(SPANS);
      expect(result.current.canFilter("metadata", "git.branch")).toBe(true);
      expect(result.current.canFilter("metadata", "provider")).toBe(true);
      expect(result.current.canFilter("input", "messages[0].content")).toBe(
        true,
      );
      expect(result.current.canFilter("metadata", "")).toBe(false);
    });

    it("blocks provider/providers for traces", () => {
      const { result } = setup(TRACES);
      expect(result.current.canFilter("metadata", "provider")).toBe(false);
      expect(result.current.canFilter("metadata", "providers[0]")).toBe(false);
      expect(result.current.canFilter("metadata", "integration")).toBe(true);
    });
  });

  describe("referential stability", () => {
    it("keeps `filter` stable across values changes and still reads the latest values", () => {
      const applyValue = vi.fn();
      const pinChip = vi.fn();
      const { result, rerender } = renderHook(
        ({ values }: { values: ChipValueMap }) =>
          useQuickAttributeFilterActions({
            type: SPANS,
            tableId: TABLE_ID,
            values,
            applyValue,
            pinChip,
          }),
        { initialProps: { values: {} as ChipValueMap } },
      );

      const firstFilter = result.current.filter;

      // Applying a filter mutates `values`; `filter` must keep its identity or
      // the CodeMirror quick-filter extension rebuilds on every chip edit.
      const existing: QueryBuilderChipValue = {
        rows: [
          {
            id: "1",
            field: "",
            type: "",
            operator: "contains",
            key: "env",
            value: "prod",
          },
        ],
      };
      rerender({ values: { metadata: existing } });
      expect(result.current.filter).toBe(firstFilter);

      // The ref means the stable callback still sees the latest values: this is
      // a duplicate of the existing row, so it pins without re-applying.
      act(() => result.current.filter("metadata", "env", "prod"));
      expect(applyValue).not.toHaveBeenCalled();
      expect(pinChip).toHaveBeenCalledWith("metadata");
    });
  });

  describe("filter applies a contains row directly (one-click, no modal)", () => {
    it("applies a metadata contains row and pins the chip", () => {
      const { result, applyValue, pinChip } = setup(TRACES);
      act(() => result.current.filter("metadata", "git.branch", "main"));

      const [chipId, value] = applyValue.mock.calls[0];
      expect(chipId).toBe("metadata");
      expect((value as QueryBuilderChipValue).rows[0]).toMatchObject({
        key: "git.branch",
        operator: "contains",
        value: "main",
      });
      expect(pinChip).toHaveBeenCalledWith("metadata");
    });

    it("prefixes input/output keys and targets the custom chip", () => {
      const { result, applyValue } = setup(SPANS);
      act(() => result.current.filter("input", "messages[0].content", "hi"));

      const [chipId, value] = applyValue.mock.calls[0];
      expect(chipId).toBe("custom");
      expect((value as QueryBuilderChipValue).rows[0]).toMatchObject({
        key: "input.messages[0].content",
        operator: "contains",
        value: "hi",
      });
    });

    it("applies a provider row without a key (spans)", () => {
      const { result, applyValue } = setup(SPANS);
      act(() => result.current.filter("metadata", "provider", "openai"));

      const [chipId, value] = applyValue.mock.calls[0];
      expect(chipId).toBe("provider");
      const row = (value as QueryBuilderChipValue).rows[0];
      expect(row).toMatchObject({ operator: "contains", value: "openai" });
      expect(row.key).toBe("");
    });

    it("stringifies non-string values", () => {
      const { result, applyValue } = setup(TRACES);
      act(() => result.current.filter("metadata", "count", 3));
      expect(
        (applyValue.mock.calls[0][1] as QueryBuilderChipValue).rows[0],
      ).toMatchObject({ operator: "contains", value: "3" });
    });

    it("is a no-op for non-filterable computed keys", () => {
      const { result, applyValue, pinChip } = setup(TRACES);
      act(() => result.current.filter("metadata", "providers[0]", "openai"));
      expect(applyValue).not.toHaveBeenCalled();
      expect(pinChip).not.toHaveBeenCalled();
    });

    it("appends to real rows and drops blank rows", () => {
      const existing: QueryBuilderChipValue = {
        rows: [
          {
            id: "1",
            field: "",
            type: "",
            operator: "=",
            key: "env",
            value: "prod",
          },
          { id: "2", field: "", type: "", operator: "", key: "", value: "" },
        ],
      };
      const { result, applyValue } = setup(SPANS, { metadata: existing });
      act(() => result.current.filter("metadata", "git.branch", "main"));

      const rows = (applyValue.mock.calls[0][1] as QueryBuilderChipValue).rows;
      expect(rows.map((r) => r.key)).toEqual(["env", "git.branch"]);
    });

    it("does not re-apply a duplicate row but still pins", () => {
      const existing: QueryBuilderChipValue = {
        rows: [
          {
            id: "1",
            field: "",
            type: "",
            operator: "contains",
            key: "git.branch",
            value: "main",
          },
        ],
      };
      const { result, applyValue, pinChip } = setup(SPANS, {
        metadata: existing,
      });
      act(() => result.current.filter("metadata", "git.branch", "main"));

      expect(applyValue).not.toHaveBeenCalled();
      expect(pinChip).toHaveBeenCalledWith("metadata");
    });

    it("appends a new contains row when an existing row has the same key but a different operator", () => {
      const existing: QueryBuilderChipValue = {
        rows: [
          {
            id: "1",
            field: "",
            type: "",
            operator: "=",
            key: "git.branch",
            value: "main",
          },
        ],
      };
      const { result, applyValue } = setup(SPANS, { metadata: existing });
      act(() => result.current.filter("metadata", "git.branch", "main"));

      const rows = (applyValue.mock.calls[0][1] as QueryBuilderChipValue).rows;
      expect(rows).toHaveLength(2);
      expect(rows[1]).toMatchObject({
        key: "git.branch",
        operator: "contains",
        value: "main",
      });
    });
  });

  describe("fires the quick-filter BI event on apply", () => {
    it("captures entity (data_type), section (source), chip and operator", () => {
      const { result } = setup(SPANS);
      act(() => result.current.filter("input", "messages[0].content", "hi"));

      expect(trackEvent).toHaveBeenCalledWith(OpikEvent.QUICK_FILTER_APPLIED, {
        data_type: SPANS,
        source: "input",
        filter_name: "custom",
        operator: "contains",
        table_id: TABLE_ID,
      });
    });

    it("reports trace metadata applies from the traces tab", () => {
      const { result } = setup(TRACES);
      act(() => result.current.filter("metadata", "git.branch", "main"));

      expect(trackEvent).toHaveBeenCalledWith(OpikEvent.QUICK_FILTER_APPLIED, {
        data_type: TRACES,
        source: "metadata",
        filter_name: "metadata",
        operator: "contains",
        table_id: TABLE_ID,
      });
    });

    it("still fires on the duplicate/no-op path (usage signal)", () => {
      const existing: QueryBuilderChipValue = {
        rows: [
          {
            id: "1",
            field: "",
            type: "",
            operator: "contains",
            key: "git.branch",
            value: "main",
          },
        ],
      };
      const { result, applyValue } = setup(SPANS, { metadata: existing });
      act(() => result.current.filter("metadata", "git.branch", "main"));

      expect(applyValue).not.toHaveBeenCalled();
      expect(trackEvent).toHaveBeenCalledWith(
        OpikEvent.QUICK_FILTER_APPLIED,
        expect.objectContaining({
          filter_name: "metadata",
          operator: "contains",
        }),
      );
    });

    it("does not fire for non-filterable keys", () => {
      const { result } = setup(TRACES);
      act(() => result.current.filter("metadata", "providers[0]", "openai"));
      expect(trackEvent).not.toHaveBeenCalled();
    });
  });
});
