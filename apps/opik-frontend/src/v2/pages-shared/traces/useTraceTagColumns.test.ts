import { act, renderHook } from "@testing-library/react";
import useLocalStorageState from "use-local-storage-state";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { COLUMN_TYPE } from "@/types/shared";
import { BaseTraceData } from "@/types/traces";
import {
  COLUMNS_TAGS_ORDER_KEY_SUFFIX,
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
    const setFilters = vi.fn();
    const setPage = vi.fn();
    const { result } = renderHook(() =>
      useTagFilterHandler({
        filters: [],
        setFilters,
        setPage,
      }),
    );

    act(() => result.current("myprotein-en-gb"));

    expect(setFilters).toHaveBeenCalledWith([
      expect.objectContaining({
        field: "tags",
        operator: "contains",
        value: "myprotein-en-gb",
      }),
    ]);
    expect(setPage).toHaveBeenCalledWith(1);
  });

  it("does not add a duplicate tags contains filter", () => {
    const setFilters = vi.fn();
    const setPage = vi.fn();
    const { result } = renderHook(() =>
      useTagFilterHandler({
        filters: [
          {
            id: "tag-filter",
            field: "tags",
            type: COLUMN_TYPE.list,
            operator: "contains",
            value: "myprotein-en-gb",
          },
        ],
        setFilters,
        setPage,
      }),
    );

    act(() => result.current("myprotein-en-gb"));

    expect(setFilters).not.toHaveBeenCalled();
    expect(setPage).not.toHaveBeenCalled();
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
      }),
    );

    expect(result.current.tagColumnsOrder).toEqual(["tags.beta"]);
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
  });
});
