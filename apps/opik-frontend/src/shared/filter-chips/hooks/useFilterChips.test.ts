import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, renderHook } from "@testing-library/react";

import { ChipDefinition } from "@/shared/filter-chips/types";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE } from "@/types/shared";

const setRawFilters = vi.fn();
const setPinnedIds = vi.fn();
let mockRawFilters: Filter[] | undefined;
let mockPinnedIds: string[] | undefined;

vi.mock("use-query-params", () => ({
  JsonParam: {},
  useQueryParam: vi.fn(() => [mockRawFilters, setRawFilters]),
}));

vi.mock("use-local-storage-state", () => ({
  default: vi.fn(() => [mockPinnedIds, setPinnedIds]),
}));

import useFilterChips from "./useFilterChips";

const booleanDef: ChipDefinition = {
  id: "with_errors",
  field: "error_info",
  label: "With errors",
  kind: "boolean",
  onOperator: "is_not_empty",
  columnType: COLUMN_TYPE.errors,
};

const singleSelectDef: ChipDefinition = {
  id: "type",
  field: "type",
  label: "Type",
  kind: "single-select",
  options: [
    { label: "LLM", value: "llm" },
    { label: "Tool", value: "tool" },
  ],
  columnType: COLUMN_TYPE.category,
  operator: "=",
};

const numericDef: ChipDefinition = {
  id: "duration",
  field: "duration",
  label: "Duration",
  kind: "numeric",
  columnType: COLUMN_TYPE.duration,
};

const DEFINITIONS: ChipDefinition[] = [booleanDef, singleSelectDef, numericDef];

const setup = (overrides?: {
  raw?: Filter[];
  pinned?: string[];
  definitions?: ChipDefinition[];
  defaultPinned?: string[];
  onChange?: () => void;
}) => {
  mockRawFilters = overrides?.raw;
  mockPinnedIds = overrides?.pinned;
  return renderHook(() =>
    useFilterChips({
      tableId: "test",
      urlKey: "test_filters",
      definitions: overrides?.definitions ?? DEFINITIONS,
      defaultPinned: overrides?.defaultPinned ?? [],
      onChange: overrides?.onChange,
    }),
  );
};

const f = (overrides: Partial<Filter>): Filter => ({
  id: overrides.id ?? "f",
  field: overrides.field ?? "",
  type: overrides.type ?? "",
  operator: overrides.operator ?? "=",
  key: overrides.key,
  value: overrides.value ?? "",
});

describe("useFilterChips", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockRawFilters = undefined;
    mockPinnedIds = undefined;
  });

  describe("initial state with empty URL", () => {
    it("chipsPinned reflects defaultPinned when localStorage is empty", () => {
      const { result } = setup({ defaultPinned: ["with_errors"] });
      expect(result.current.chipsPinned.map((d) => d.id)).toEqual([
        "with_errors",
      ]);
    });

    it("chipsUnpinned is the complement of pinnedIds within definitions", () => {
      const { result } = setup({ defaultPinned: ["with_errors"] });
      expect(result.current.chipsUnpinned.map((d) => d.id)).toEqual([
        "type",
        "duration",
      ]);
    });

    it("values and filters are empty", () => {
      const { result } = setup();
      expect(result.current.values).toEqual({});
      expect(result.current.filters).toEqual([]);
    });
  });

  describe("URL deep-link populates values via sanitizeFilters", () => {
    it("a valid URL filter becomes a chip value", () => {
      const { result } = setup({
        raw: [
          f({
            field: "type",
            operator: "=",
            value: "llm",
            type: COLUMN_TYPE.category,
          }),
        ],
      });
      expect(result.current.values).toEqual({ type: { value: "llm" } });
    });

    it("filters output mirrors the chip value", () => {
      const { result } = setup({
        raw: [
          f({
            field: "type",
            operator: "=",
            value: "llm",
            type: COLUMN_TYPE.category,
          }),
        ],
      });
      expect(result.current.filters).toHaveLength(1);
      expect(result.current.filters[0]).toMatchObject({
        field: "type",
        operator: "=",
        value: "llm",
      });
    });
  });

  describe("chipsPinned = pinnedIds ∪ appliedIds", () => {
    it("an applied chip shows up in chipsPinned even when not in pinnedIds", () => {
      const { result } = setup({
        raw: [
          f({
            field: "type",
            operator: "=",
            value: "llm",
            type: COLUMN_TYPE.category,
          }),
        ],
        pinned: ["with_errors"],
      });
      const pinned = result.current.chipsPinned.map((d) => d.id);
      expect(pinned).toContain("with_errors");
      expect(pinned).toContain("type");
    });

    it("chipsUnpinned excludes both pinned and applied", () => {
      const { result } = setup({
        raw: [
          f({
            field: "type",
            operator: "=",
            value: "llm",
            type: COLUMN_TYPE.category,
          }),
        ],
        pinned: ["with_errors"],
      });
      const unpinned = result.current.chipsUnpinned.map((d) => d.id);
      expect(unpinned).not.toContain("with_errors");
      expect(unpinned).not.toContain("type");
      expect(unpinned).toContain("duration");
    });

    it("when nothing is applied, chipsPinned matches pinnedIds exactly", () => {
      const { result } = setup({ pinned: ["with_errors", "type"] });
      expect(result.current.chipsPinned.map((d) => d.id)).toEqual([
        "with_errors",
        "type",
      ]);
    });
  });

  describe("applyValue", () => {
    it("writes the new filter to the URL", () => {
      const { result } = setup();
      act(() => result.current.applyValue("type", { value: "llm" }));
      expect(setRawFilters).toHaveBeenCalledOnce();
      const updater = setRawFilters.mock.calls[0][0];
      const next = updater(undefined);
      expect(next).toHaveLength(1);
      expect(next[0]).toMatchObject({
        field: "type",
        operator: "=",
        value: "llm",
      });
    });

    it("auto-pins the chip via localStorage", () => {
      const { result } = setup();
      act(() => result.current.applyValue("type", { value: "llm" }));
      expect(setPinnedIds).toHaveBeenCalledOnce();
      const updater = setPinnedIds.mock.calls[0][0];
      expect(updater([])).toEqual(["type"]);
    });

    it("is idempotent on the pinned list when the chip is already pinned", () => {
      const { result } = setup({ pinned: ["type"] });
      act(() => result.current.applyValue("type", { value: "tool" }));
      const updater = setPinnedIds.mock.calls[0][0];
      expect(updater(["type"])).toEqual(["type"]);
    });
  });

  describe("clearValue", () => {
    it("removes the chip's filter from the URL", () => {
      const raw = [
        f({
          field: "type",
          operator: "=",
          value: "llm",
          type: COLUMN_TYPE.category,
        }),
      ];
      const { result } = setup({ raw });
      act(() => result.current.clearValue("type"));
      const updater = setRawFilters.mock.calls[0][0];
      expect(updater(raw)).toBeUndefined();
    });

    it("does not touch pinnedIds", () => {
      const raw = [
        f({
          field: "type",
          operator: "=",
          value: "llm",
          type: COLUMN_TYPE.category,
        }),
      ];
      const { result } = setup({ raw });
      act(() => result.current.clearValue("type"));
      expect(setPinnedIds).not.toHaveBeenCalled();
    });
  });

  describe("clearAll", () => {
    it("writes undefined to the URL", () => {
      const raw = [
        f({
          field: "type",
          operator: "=",
          value: "llm",
          type: COLUMN_TYPE.category,
        }),
      ];
      const { result } = setup({ raw });
      act(() => result.current.clearAll());
      const updater = setRawFilters.mock.calls[0][0];
      expect(updater(raw)).toBeUndefined();
    });

    it("does not touch pinnedIds", () => {
      const { result } = setup();
      act(() => result.current.clearAll());
      expect(setPinnedIds).not.toHaveBeenCalled();
    });
  });

  describe("pinChip", () => {
    it("adds the id to pinnedIds", () => {
      const { result } = setup();
      act(() => result.current.pinChip("type"));
      expect(setPinnedIds).toHaveBeenCalledOnce();
      const updater = setPinnedIds.mock.calls[0][0];
      expect(updater([])).toEqual(["type"]);
    });

    it("is idempotent when the chip is already pinned", () => {
      const { result } = setup();
      act(() => result.current.pinChip("type"));
      const updater = setPinnedIds.mock.calls[0][0];
      expect(updater(["type"])).toEqual(["type"]);
    });

    it("does not touch the URL", () => {
      const { result } = setup();
      act(() => result.current.pinChip("type"));
      expect(setRawFilters).not.toHaveBeenCalled();
    });
  });

  describe("unpinChip", () => {
    it("removes the id from pinnedIds", () => {
      const { result } = setup();
      act(() => result.current.unpinChip("type"));
      expect(setPinnedIds).toHaveBeenCalledOnce();
      const updater = setPinnedIds.mock.calls[0][0];
      expect(updater(["type", "with_errors"])).toEqual(["with_errors"]);
    });

    it("also clears the chip's URL value", () => {
      const raw = [
        f({
          field: "type",
          operator: "=",
          value: "llm",
          type: COLUMN_TYPE.category,
        }),
      ];
      const { result } = setup({ raw });
      act(() => result.current.unpinChip("type"));
      expect(setRawFilters).toHaveBeenCalledOnce();
      const updater = setRawFilters.mock.calls[0][0];
      expect(updater(raw)).toBeUndefined();
    });
  });

  describe("onChange firing", () => {
    it("does not fire on initial mount with empty URL", () => {
      const onChange = vi.fn();
      setup({ onChange });
      expect(onChange).not.toHaveBeenCalled();
    });

    it("does not fire on initial mount when URL already has filters", () => {
      const onChange = vi.fn();
      setup({
        raw: [
          f({
            field: "type",
            operator: "=",
            value: "llm",
            type: COLUMN_TYPE.category,
          }),
        ],
        onChange,
      });
      expect(onChange).not.toHaveBeenCalled();
    });

    it("fires when filters content changes between renders", () => {
      const onChange = vi.fn();
      const { rerender } = setup({ onChange });
      mockRawFilters = [
        f({
          field: "type",
          operator: "=",
          value: "llm",
          type: COLUMN_TYPE.category,
        }),
      ];
      rerender();
      expect(onChange).toHaveBeenCalled();
    });
  });

  describe("console.info on sanitize drops", () => {
    let infoSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
      infoSpy = vi.spyOn(console, "info").mockImplementation(() => {});
    });

    afterEach(() => {
      infoSpy.mockRestore();
    });

    it("logs when sanitizer drops items", () => {
      setup({
        raw: [
          f({
            field: "unknown_field",
            operator: "=",
            value: "x",
            type: COLUMN_TYPE.string,
          }),
        ],
      });
      expect(infoSpy).toHaveBeenCalled();
    });

    it("does not log when nothing is dropped", () => {
      setup({
        raw: [
          f({
            field: "type",
            operator: "=",
            value: "llm",
            type: COLUMN_TYPE.category,
          }),
        ],
      });
      expect(infoSpy).not.toHaveBeenCalled();
    });
  });
});
