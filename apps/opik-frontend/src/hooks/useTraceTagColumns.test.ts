import { act, renderHook } from "@testing-library/react";
import useLocalStorageState from "use-local-storage-state";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE } from "@/types/shared";
import { BaseTraceData } from "@/types/traces";
import {
  COLUMNS_TAGS_ORDER_KEY_SUFFIX,
  getTraceDynamicColumnIdsExcludedFromSelectAll,
  useTagFilterHandler,
  useTraceTagColumns,
} from "./useTraceTagColumns";

const mocks = vi.hoisted(() => ({
  setTagColumnsOrder: vi.fn(),
  tagColumnsOrder: [] as string[],
}));

vi.mock("use-local-storage-state", () => ({
  default: vi.fn(() => [mocks.tagColumnsOrder, mocks.setTagColumnsOrder]),
}));

describe("useTagFilterHandler", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("adds a tags contains filter and resets pagination", () => {
    let nextFilters: unknown;
    const setFilters = vi.fn((updater) => {
      nextFilters = typeof updater === "function" ? updater([]) : updater;
    });
    const setPage = vi.fn();
    const { result } = renderHook(() =>
      useTagFilterHandler({
        setFilters,
        setPage,
      }),
    );

    act(() => result.current("myprotein-en-gb"));

    expect(setFilters).toHaveBeenCalledWith(expect.any(Function));
    expect(nextFilters).toEqual([
      expect.objectContaining({
        field: "tags",
        operator: "contains",
        value: "myprotein-en-gb",
      }),
    ]);
    expect(setPage).toHaveBeenCalledWith(1);
  });

  it("does not add a duplicate tags contains filter", () => {
    const currentFilters: Filter[] = [
      {
        id: "tag-filter",
        field: "tags",
        type: COLUMN_TYPE.list,
        operator: "contains",
        value: "myprotein-en-gb",
      },
    ];
    let nextFilters: unknown;
    const setFilters = vi.fn((updater) => {
      nextFilters =
        typeof updater === "function" ? updater(currentFilters) : updater;
    });
    const setPage = vi.fn();
    const { result } = renderHook(() =>
      useTagFilterHandler({
        setFilters,
        setPage,
      }),
    );

    act(() => result.current("myprotein-en-gb"));

    expect(setFilters).toHaveBeenCalledWith(expect.any(Function));
    expect(nextFilters).toBe(currentFilters);
    expect(setPage).not.toHaveBeenCalled();
  });

  it("dedupes against the latest filters when rapid clicks enqueue updates", () => {
    let currentFilters: Filter[] = [];
    const setFilters = vi.fn((updater) => {
      currentFilters =
        typeof updater === "function" ? updater(currentFilters) : updater;
    });
    const setPage = vi.fn();
    const { result } = renderHook(() =>
      useTagFilterHandler({
        setFilters,
        setPage,
      }),
    );

    act(() => {
      result.current("myprotein-en-gb");
      result.current("myprotein-en-gb");
    });

    expect(currentFilters).toHaveLength(1);
    expect(currentFilters[0]).toMatchObject({
      field: "tags",
      operator: "contains",
      value: "myprotein-en-gb",
    });
    expect(setPage).toHaveBeenCalledTimes(1);
    expect(setPage).toHaveBeenCalledWith(1);
  });

  it("resets pagination after the filter updater has completed", () => {
    let isApplyingFilters = false;
    const setFilters = vi.fn((updater) => {
      isApplyingFilters = true;
      if (typeof updater === "function") {
        updater([]);
      }
      isApplyingFilters = false;
    });
    const setPage = vi.fn(() => {
      expect(isApplyingFilters).toBe(false);
    });
    const { result } = renderHook(() =>
      useTagFilterHandler({
        setFilters,
        setPage,
      }),
    );

    act(() => result.current("myprotein-en-gb"));

    expect(setPage).toHaveBeenCalledWith(1);
  });
});

describe("useTraceTagColumns", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.tagColumnsOrder = [];
  });

  it("stores tag column ordering with the shared key suffix", () => {
    renderHook(() =>
      useTraceTagColumns({
        rows: [],
        storageKey: `traces-${COLUMNS_TAGS_ORDER_KEY_SUFFIX}`,
      }),
    );

    expect(useLocalStorageState).toHaveBeenCalledWith(
      "traces-tag-columns-order",
      {
        defaultValue: [],
      },
    );
  });

  it("builds sorted tag columns and select-all exclusions from row tags", () => {
    mocks.tagColumnsOrder = ["tags.beta"];

    const { result } = renderHook(() =>
      useTraceTagColumns({
        rows: [
          { tags: ["beta", "alpha", "beta"] },
          { tags: ["myprotein-en-gb"] },
        ],
        storageKey: "trace-tags",
        selectedColumns: ["tags.alpha"],
        sortableColumns: ["tags.*"],
      }),
    );

    expect(
      result.current.tagColumnsData.map(({ id, label, type, sortable }) => ({
        id,
        label,
        type,
        sortable,
      })),
    ).toEqual([
      {
        id: "tags.alpha",
        label: "alpha",
        type: COLUMN_TYPE.string,
        sortable: false,
      },
      {
        id: "tags.beta",
        label: "beta",
        type: COLUMN_TYPE.string,
        sortable: false,
      },
      {
        id: "tags.myprotein-en-gb",
        label: "myprotein-en-gb",
        type: COLUMN_TYPE.string,
        sortable: false,
      },
    ]);
    expect(result.current.tagColumnIdsExcludedFromSelectAll).toEqual([
      "tags.alpha",
      "tags.beta",
      "tags.myprotein-en-gb",
    ]);
    expect(
      result.current.tagColumnsData[0].accessorFn?.({
        tags: ["alpha"],
      } as BaseTraceData),
    ).toBe("Yes");
    expect(
      result.current.tagColumnsData[0].accessorFn?.({
        tags: ["beta"],
      } as BaseTraceData),
    ).toBe("-");
    expect(
      result.current.tagColumns.map(
        (column) => "accessorKey" in column && column.accessorKey,
      ),
    ).toEqual(["tags.alpha"]);
    expect(result.current.tagColumnSection).toMatchObject({
      title: "Tag values",
      columns: result.current.tagColumnsData,
      order: ["tags.beta"],
    });
  });
});

describe("getTraceDynamicColumnIdsExcludedFromSelectAll", () => {
  it("combines metadata and tag column ids", () => {
    expect(
      getTraceDynamicColumnIdsExcludedFromSelectAll(
        [{ id: "metadata.user" }],
        ["tags.alpha"],
      ),
    ).toEqual(["metadata.user", "tags.alpha"]);
  });
});
