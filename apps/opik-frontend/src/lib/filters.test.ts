import { describe, expect, it } from "vitest";
import {
  buildDatasetFilterColumns,
  generateLogsSourceFilter,
  processFilters,
  transformDataColumnFilters,
} from "./filters";
import {
  COLUMN_DATA_ID,
  COLUMN_TYPE,
  DYNAMIC_COLUMN_TYPE,
} from "@/types/shared";
import { Filter } from "@/types/filters";
import { LOGS_SOURCE } from "@/types/traces";

const createFilter = (
  overrides: Partial<Filter> &
    Pick<Filter, "id" | "field" | "operator" | "value">,
): Filter => ({
  type: COLUMN_TYPE.string,
  key: "",
  ...overrides,
});

describe("buildDatasetFilterColumns", () => {
  it("should return tags column for empty dataset columns", () => {
    const result = buildDatasetFilterColumns([]);
    expect(result).toEqual([
      { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
    ]);
  });

  it("should map dataset columns with COLUMN_DATA_ID prefix", () => {
    const result = buildDatasetFilterColumns([
      { name: "input", types: [DYNAMIC_COLUMN_TYPE.string] },
      { name: "score", types: [DYNAMIC_COLUMN_TYPE.number] },
    ]);
    expect(result).toEqual([
      {
        id: `${COLUMN_DATA_ID}.input`,
        label: "input",
        type: COLUMN_TYPE.string,
      },
      {
        id: `${COLUMN_DATA_ID}.score`,
        label: "score",
        type: COLUMN_TYPE.number,
      },
      { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
    ]);
  });

  it("should include ID column when includeId is true", () => {
    const result = buildDatasetFilterColumns([], true);
    expect(result).toEqual([
      { id: "id", label: "ID", type: COLUMN_TYPE.string },
      { id: "tags", label: "Tags", type: COLUMN_TYPE.list, iconType: "tags" },
    ]);
  });

  it("should map object types to dictionary", () => {
    const result = buildDatasetFilterColumns([
      { name: "metadata", types: [DYNAMIC_COLUMN_TYPE.object] },
    ]);
    expect(result[0]).toEqual({
      id: `${COLUMN_DATA_ID}.metadata`,
      label: "metadata",
      type: COLUMN_TYPE.dictionary,
    });
  });

  it("should map array types to list", () => {
    const result = buildDatasetFilterColumns([
      { name: "tags_col", types: [DYNAMIC_COLUMN_TYPE.array] },
    ]);
    expect(result[0]).toEqual({
      id: `${COLUMN_DATA_ID}.tags_col`,
      label: "tags_col",
      type: COLUMN_TYPE.list,
    });
  });
});

describe("transformDataColumnFilters", () => {
  it("should transform COLUMN_DATA_ID.columnName to field=COLUMN_DATA_ID with key=columnName", () => {
    const filters = [
      createFilter({
        id: "1",
        field: `${COLUMN_DATA_ID}.input`,
        operator: "contains",
        value: "hello",
      }),
    ];
    const result = transformDataColumnFilters(filters);
    expect(result[0]).toMatchObject({ field: COLUMN_DATA_ID, key: "input" });
  });

  it("should not transform non-data fields", () => {
    const filters = [
      createFilter({ id: "1", field: "id", operator: "=", value: "abc" }),
    ];
    const result = transformDataColumnFilters(filters);
    expect(result).toEqual(filters);
  });

  it("should handle empty filters array", () => {
    expect(transformDataColumnFilters([])).toEqual([]);
  });

  it("should handle mixed data and non-data filters", () => {
    const filters = [
      createFilter({
        id: "1",
        field: `${COLUMN_DATA_ID}.input`,
        operator: "contains",
        value: "hello",
      }),
      createFilter({
        id: "2",
        field: "tags",
        type: COLUMN_TYPE.list,
        operator: "contains",
        value: "test",
      }),
      createFilter({
        id: "3",
        field: `${COLUMN_DATA_ID}.score`,
        type: COLUMN_TYPE.number,
        operator: ">",
        value: "5",
      }),
    ];
    const result = transformDataColumnFilters(filters);
    expect(result[0]).toMatchObject({ field: COLUMN_DATA_ID, key: "input" });
    expect(result[1]).toMatchObject({ field: "tags", key: "" });
    expect(result[2]).toMatchObject({ field: COLUMN_DATA_ID, key: "score" });
  });
});

describe("generateLogsSourceFilter", () => {
  const toBackendFilters = (source: LOGS_SOURCE) =>
    JSON.parse(
      processFilters(undefined, generateLogsSourceFilter(source)).filters!,
    );

  it("scopes playground logs to single runs and playground experiments only", () => {
    expect(toBackendFilters(LOGS_SOURCE.playground)).toEqual([
      {
        field: "source",
        type: COLUMN_TYPE.string,
        operator: "in",
        value: "playground,experiment",
      },
      {
        field: "metadata",
        type: COLUMN_TYPE.dictionary,
        operator: "=",
        key: "created_from",
        value: "playground",
      },
    ]);
  });

  it.each([
    LOGS_SOURCE.sdk,
    LOGS_SOURCE.experiment,
    LOGS_SOURCE.optimization,
    LOGS_SOURCE.evaluator,
  ])("filters %s logs by source alone", (source) => {
    expect(toBackendFilters(source)).toEqual([
      {
        field: "source",
        type: COLUMN_TYPE.string,
        operator: "=",
        value: source,
      },
    ]);
  });
});
