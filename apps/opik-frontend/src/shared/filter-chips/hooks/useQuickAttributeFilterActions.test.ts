import { describe, it, expect, vi } from "vitest";
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

const SPANS = TRACE_DATA_TYPE.spans;
const TRACES = TRACE_DATA_TYPE.traces;

const qb = (
  id: string,
  label: string,
  columnType: COLUMN_TYPE,
  operators: FilterOperator[],
): ChipDefinition => ({
  id,
  field: id,
  label,
  kind: "query-builder",
  columnType,
  operators,
  defaultOperator: operators[0],
});

const DEFINITIONS: ChipDefinition[] = [
  qb("metadata", "Metadata", COLUMN_TYPE.dictionary, ["=", "contains"]),
  qb("custom", "Custom filter", COLUMN_TYPE.dictionary, ["=", "contains"]),
  qb("provider", "Provider", COLUMN_TYPE.string, ["=", "contains"]),
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
    it("seeds a metadata draft with the chip operators", () => {
      const { result, applyValue } = setup(TRACES);
      act(() => result.current.filter("metadata", "git.branch", "main"));

      expect(result.current.dialog.draft).toMatchObject({
        chipId: "metadata",
        key: "git.branch",
        field: "git.branch",
        defaultOperator: "=",
        value: "main",
      });
      expect(result.current.dialog.draft?.operators).toEqual(["=", "contains"]);
      expect(applyValue).not.toHaveBeenCalled();
    });

    it("seeds a span provider draft routed to the provider field", () => {
      const { result } = setup(SPANS);
      act(() => result.current.filter("metadata", "provider", "openai"));

      expect(result.current.dialog.draft).toMatchObject({
        chipId: "provider",
        field: "Provider",
        value: "openai",
      });
      expect(result.current.dialog.draft?.key).toBeUndefined();
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
});
