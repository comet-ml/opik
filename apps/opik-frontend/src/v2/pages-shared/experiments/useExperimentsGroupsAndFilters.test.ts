import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";

import { COLUMN_DATASET_ID } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";
import { DATASET_TYPE } from "@/types/datasets";

// Only the router-coupled half is mocked; storage is real so a saved value is
// read back exactly as it would be in the browser.
vi.mock("use-query-params", () => ({
  JsonParam: {},
  useQueryParam: () => [undefined, vi.fn()],
}));

import { useExperimentsGroupsAndFilters } from "./useExperimentsGroupsAndFilters";

const STORAGE_KEY_PREFIX = "test-experiments";
const GROUPS_STORAGE_KEY = `${STORAGE_KEY_PREFIX}-columns-groups`;

const renderGroups = () =>
  renderHook(() =>
    useExperimentsGroupsAndFilters({
      storageKeyPrefix: STORAGE_KEY_PREFIX,
      sortedColumns: [],
      filters: [],
    }),
  ).result.current.groups;

describe("useExperimentsGroupsAndFilters", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("groups by item source when nothing is saved", () => {
    expect(renderGroups()).toEqual([
      expect.objectContaining({
        field: COLUMN_DATASET_ID,
        direction: SORT_DIRECTION.ASC,
      }),
    ]);
  });

  it("keeps a saved empty grouping instead of applying the default", () => {
    localStorage.setItem(GROUPS_STORAGE_KEY, "[]");

    expect(renderGroups()).toEqual([]);
  });

  it("limits the item source picker to the tab's dataset type", () => {
    const { result } = renderHook(() =>
      useExperimentsGroupsAndFilters({
        storageKeyPrefix: STORAGE_KEY_PREFIX,
        sortedColumns: [],
        filters: [],
        datasetType: DATASET_TYPE.TEST_SUITE,
      }),
    );

    expect(
      result.current.filtersAndGroupsConfig.rowsMap[COLUMN_DATASET_ID]
        .keyComponentProps,
    ).toEqual(
      expect.objectContaining({
        filters: [
          expect.objectContaining({
            field: "type",
            operator: "=",
            value: DATASET_TYPE.TEST_SUITE,
          }),
        ],
      }),
    );
  });

  it("does not filter the item source picker without a dataset type", () => {
    const { result } = renderHook(() =>
      useExperimentsGroupsAndFilters({
        storageKeyPrefix: STORAGE_KEY_PREFIX,
        sortedColumns: [],
        filters: [],
      }),
    );

    expect(
      result.current.filtersAndGroupsConfig.rowsMap[COLUMN_DATASET_ID]
        .keyComponentProps,
    ).not.toHaveProperty("filters");
  });
});
