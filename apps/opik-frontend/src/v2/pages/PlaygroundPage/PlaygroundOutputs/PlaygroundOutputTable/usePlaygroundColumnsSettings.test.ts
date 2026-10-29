import { beforeEach, describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";

import usePlaygroundColumnsSettings from "./usePlaygroundColumnsSettings";

const COLUMN_IDS = ["variables.question", "variables.answer", "tags"];

const renderSettings = (datasetId: string, columnIds: string[] = COLUMN_IDS) =>
  renderHook(
    ({ id, columnIds }) => usePlaygroundColumnsSettings(id, columnIds),
    {
      initialProps: { id: datasetId, columnIds },
    },
  );

describe("usePlaygroundColumnsSettings", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("should show every column of a dataset nobody has customized", () => {
    const { result } = renderSettings("dataset-a");

    expect(result.current.selectedColumns).toEqual(COLUMN_IDS);
    expect(result.current.columnsOrder).toEqual([]);
  });

  it("should hide columns only for the dataset they were hidden in", () => {
    const { result, rerender } = renderSettings("dataset-a");

    act(() => result.current.setSelectedColumns(["variables.answer"]));

    expect(result.current.selectedColumns).toEqual(["variables.answer"]);

    rerender({ id: "dataset-b", columnIds: COLUMN_IDS });
    expect(result.current.selectedColumns).toEqual(COLUMN_IDS);

    rerender({ id: "dataset-a", columnIds: COLUMN_IDS });
    expect(result.current.selectedColumns).toEqual(["variables.answer"]);
  });

  it("should still hide the columns after the page is reloaded", () => {
    const first = renderSettings("dataset-a");
    act(() => first.result.current.setSelectedColumns(["tags"]));
    first.unmount();

    const { result } = renderSettings("dataset-a");

    expect(result.current.selectedColumns).toEqual(["tags"]);
  });

  it("should show a field that was added to the dataset after columns were hidden", () => {
    const { result, rerender } = renderSettings("dataset-a");
    act(() => result.current.setSelectedColumns(["variables.answer", "tags"]));

    rerender({
      id: "dataset-a",
      columnIds: [...COLUMN_IDS, "variables.context"],
    });

    expect(result.current.selectedColumns).toEqual([
      "variables.answer",
      "tags",
      "variables.context",
    ]);
  });

  it("should keep a column hidden while the loaded rows do not have it", () => {
    const { result, rerender } = renderSettings("dataset-a");
    act(() => result.current.setSelectedColumns(["variables.answer", "tags"]));

    rerender({ id: "dataset-a", columnIds: ["variables.answer", "tags"] });
    act(() => result.current.setSelectedColumns(["variables.answer"]));
    rerender({ id: "dataset-a", columnIds: COLUMN_IDS });

    expect(result.current.selectedColumns).toEqual(["variables.answer"]);
  });

  it("should keep the column order for each dataset", () => {
    const { result, rerender } = renderSettings("dataset-a");
    const order = ["tags", "variables.question", "variables.answer"];

    act(() => result.current.setColumnsOrder(order));
    expect(result.current.columnsOrder).toEqual(order);

    rerender({ id: "dataset-b", columnIds: COLUMN_IDS });
    expect(result.current.columnsOrder).toEqual([]);
  });

  it("should share the choice between the Columns button and the table", () => {
    const button = renderSettings("dataset-a");
    const table = renderSettings("dataset-a");

    act(() => button.result.current.setSelectedColumns(["tags"]));

    expect(table.result.current.selectedColumns).toEqual(["tags"]);
  });
});
