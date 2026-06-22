import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import {
  resolveQuickFilterTarget,
  stringifyFilterValue,
  useQuickAttributeFilterActions,
} from "./useQuickAttributeFilterActions";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { FilterOperator } from "@/types/filters";
import {
  ChipDefinition,
  QueryBuilderChipValue,
} from "@/shared/filter-chips/types";
import { COLUMN_TYPE } from "@/types/shared";
import {
  DICTIONARY_OPERATORS,
  STRING_OPERATORS,
} from "@/shared/filter-chips/chips/QueryBuilderChip/operators";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

vi.mock("@/lib/analytics/tracking", async (importOriginal) => {
  const actual =
    await importOriginal<typeof import("@/lib/analytics/tracking")>();
  return { ...actual, trackEvent: vi.fn() };
});

const SPANS = TRACE_DATA_TYPE.spans;
const TRACES = TRACE_DATA_TYPE.traces;

const qb = (
  id: string,
  label: string,
  columnType: COLUMN_TYPE,
  operators: FilterOperator[],
  defaultOperator: FilterOperator = operators[0],
): ChipDefinition => ({
  id,
  field: id,
  label,
  kind: "query-builder",
  columnType,
  operators,
  defaultOperator,
});

// Use the real production operator sets so fixtures match the live chips.
// Note DICTIONARY_OPERATORS leads with "=", so the "contains" default on the
// metadata/custom chips comes from their explicit defaultOperator, not [0].
const DEFINITIONS: ChipDefinition[] = [
  qb(
    "metadata",
    "Metadata",
    COLUMN_TYPE.dictionary,
    DICTIONARY_OPERATORS,
    "contains",
  ),
  qb(
    "custom",
    "Custom filter",
    COLUMN_TYPE.dictionary,
    DICTIONARY_OPERATORS,
    "contains",
  ),
  qb("provider", "Provider", COLUMN_TYPE.string, STRING_OPERATORS, "contains"),
];

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
  const setup = (type = SPANS, values = {}, definitions = DEFINITIONS) => {
    const applyValue = vi.fn();
    const pinChip = vi.fn();
    const { result } = renderHook(() =>
      useQuickAttributeFilterActions({
        type,
        definitions,
        values,
        applyValue,
        pinChip,
      }),
    );
    return { result, applyValue, pinChip };
  };

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

  describe("filter opens an approval dialog (does not apply yet)", () => {
    it("seeds a metadata draft inheriting the chip's default operator", () => {
      const { result, applyValue } = setup(TRACES);
      act(() => result.current.filter("metadata", "git.branch", "main"));

      expect(result.current.dialog.draft).toMatchObject({
        chipId: "metadata",
        key: "git.branch",
        field: "git.branch",
        // metadata/custom dictionary chips default to "contains", not "="
        defaultOperator: "contains",
        value: "main",
      });
      expect(result.current.dialog.draft?.operators).toEqual(
        DICTIONARY_OPERATORS,
      );
      expect(applyValue).not.toHaveBeenCalled();
    });

    it("seeds a span provider draft routed to the provider field", () => {
      const { result } = setup(SPANS);
      act(() => result.current.filter("metadata", "provider", "openai"));

      expect(result.current.dialog.draft).toMatchObject({
        chipId: "provider",
        field: "Provider",
        // provider now defaults to "contains" like the other string chips
        defaultOperator: "contains",
        value: "openai",
      });
      expect(result.current.dialog.draft?.key).toBeUndefined();
    });

    it("falls back to the chip's first operator when no defaultOperator is set", () => {
      // mirrors the chip popover: defaultOperator ?? operators[0]. Use a chip
      // whose operators[0] ("contains" for STRING_OPERATORS) differs from the
      // hard fallback ("=") so this proves operators[0] is used.
      const noDefault: ChipDefinition[] = [
        qb("provider", "Provider", COLUMN_TYPE.string, STRING_OPERATORS),
      ];
      const { result } = setup(SPANS, {}, noDefault);
      act(() => result.current.filter("metadata", "provider", "openai"));
      expect(STRING_OPERATORS[0]).toBe("contains");
      expect(result.current.dialog.draft?.defaultOperator).toBe("contains");
    });

    it("records the originating section on the draft", () => {
      const { result } = setup(SPANS);
      act(() => result.current.filter("input", "messages[0].content", "hi"));
      expect(result.current.dialog.draft?.section).toBe("input");
    });

    it("is a no-op for non-filterable computed keys", () => {
      const { result } = setup(TRACES);
      act(() => result.current.filter("metadata", "providers[0]", "openai"));
      expect(result.current.dialog.draft).toBeNull();
    });

    it("falls back to ['='] and the chip id when no definition is found", () => {
      const { result } = setup(TRACES, {}, []);
      act(() => result.current.filter("metadata", "git.branch", "main"));
      expect(result.current.dialog.draft?.operators).toEqual(["="]);
      expect(result.current.dialog.draft?.defaultOperator).toBe("=");
      expect(result.current.dialog.draft?.chipLabel).toBe("metadata");
    });
  });

  describe("dialog lifecycle", () => {
    it("onClose clears the draft", () => {
      const { result } = setup(TRACES);
      act(() => result.current.filter("metadata", "git.branch", "main"));
      expect(result.current.dialog.draft).not.toBeNull();
      act(() => result.current.dialog.onClose());
      expect(result.current.dialog.draft).toBeNull();
    });

    it("onApply with no active draft is a no-op", () => {
      const { result, applyValue, pinChip } = setup(TRACES);
      act(() => result.current.dialog.onApply("=", "main"));
      expect(applyValue).not.toHaveBeenCalled();
      expect(pinChip).not.toHaveBeenCalled();
    });
  });

  describe("dialog.onApply commits the filter", () => {
    it("applies the chosen operator/value, pins the chip, and closes", () => {
      const { result, applyValue, pinChip } = setup(TRACES);
      act(() => result.current.filter("metadata", "git.branch", "main"));
      act(() => result.current.dialog.onApply("contains", "mai"));

      const [chipId, value] = applyValue.mock.calls[0];
      expect(chipId).toBe("metadata");
      expect((value as QueryBuilderChipValue).rows[0]).toMatchObject({
        key: "git.branch",
        operator: "contains",
        value: "mai",
      });
      expect(pinChip).toHaveBeenCalledWith("metadata");
      expect(result.current.dialog.draft).toBeNull();
    });

    it("applies a provider field row without a key", () => {
      const { result, applyValue } = setup(SPANS);
      act(() => result.current.filter("metadata", "provider", "openai"));
      act(() => result.current.dialog.onApply("=", "openai"));

      const [chipId, value] = applyValue.mock.calls[0];
      expect(chipId).toBe("provider");
      const row = (value as QueryBuilderChipValue).rows[0];
      expect(row).toMatchObject({ operator: "=", value: "openai" });
      expect(row.key).toBe("");
    });

    it("appends to real rows and drops blank drafts", () => {
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
      act(() => result.current.dialog.onApply("=", "main"));

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
            operator: "=",
            key: "git.branch",
            value: "main",
          },
        ],
      };
      const { result, applyValue, pinChip } = setup(SPANS, {
        metadata: existing,
      });
      act(() => result.current.filter("metadata", "git.branch", "main"));
      act(() => result.current.dialog.onApply("=", "main"));

      expect(applyValue).not.toHaveBeenCalled();
      expect(pinChip).toHaveBeenCalledWith("metadata");
    });
  });

  describe("fires the quick-filter BI event on apply", () => {
    beforeEach(() => vi.mocked(trackEvent).mockClear());

    it("captures the entity (data_type) and section (source) dimensions", () => {
      const { result } = setup(SPANS);
      act(() => result.current.filter("input", "messages[0].content", "hi"));
      act(() => result.current.dialog.onApply("contains", "hi"));

      expect(trackEvent).toHaveBeenCalledWith(OpikEvent.QUICK_FILTER_APPLIED, {
        data_type: SPANS,
        source: "input",
        filter_name: "custom",
        operator: "contains",
      });
    });

    it("reports trace metadata applies from the traces tab", () => {
      const { result } = setup(TRACES);
      act(() => result.current.filter("metadata", "git.branch", "main"));
      act(() => result.current.dialog.onApply("=", "main"));

      expect(trackEvent).toHaveBeenCalledWith(OpikEvent.QUICK_FILTER_APPLIED, {
        data_type: TRACES,
        source: "metadata",
        filter_name: "metadata",
        operator: "=",
      });
    });

    it("still fires on the duplicate/no-op path (usage signal, not a state change)", () => {
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
      act(() => result.current.dialog.onApply("=", "main"));

      expect(applyValue).not.toHaveBeenCalled();
      expect(trackEvent).toHaveBeenCalledWith(
        OpikEvent.QUICK_FILTER_APPLIED,
        expect.objectContaining({ filter_name: "metadata", operator: "=" }),
      );
    });
  });
});
