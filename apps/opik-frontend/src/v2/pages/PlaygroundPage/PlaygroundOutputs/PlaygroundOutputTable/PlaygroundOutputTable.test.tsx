import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import { DatasetItemColumn } from "@/types/datasets";
import PlaygroundOutputTable from "./PlaygroundOutputTable";

vi.mock("@/v2/pages/PlaygroundPage/useIncrementalDatasetHydration", () => ({
  useIncrementalDatasetHydration: <T,>(items: T[]) => items,
}));

const DATASET_COLUMNS: DatasetItemColumn[] = [
  { name: "question", types: [] },
  { name: "answer", types: [] },
  { name: "context", types: [] },
];

const renderTable = (datasetId: string) =>
  render(
    <TooltipProvider>
      <PlaygroundOutputTable
        datasetId={datasetId}
        datasetItems={[]}
        datasetColumns={DATASET_COLUMNS}
        promptIds={[]}
        isLoadingDatasetItems={false}
        isFetchingData={false}
      />
    </TooltipProvider>,
  );

const headerLabels = () =>
  Array.from(
    screen
      .getByTestId("playground-variables-table-header")
      .querySelectorAll("th[data-header-id]"),
  ).map((th) => th.textContent);

describe("PlaygroundOutputTable", () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem(
      "playground-output-table-hidden-columns",
      JSON.stringify({ "dataset-a": ["variables.context", "tags"] }),
    );
  });

  it("should leave out the columns hidden for this dataset", () => {
    renderTable("dataset-a");

    expect(headerLabels()).toEqual(["answer", "question"]);
  });

  it("should show every column of a dataset that has none hidden", () => {
    renderTable("dataset-b");

    expect(headerLabels()).toEqual(["answer", "context", "question", "Tags"]);
  });

  it("should lay the columns out in the order saved for this dataset", () => {
    localStorage.setItem(
      "playground-output-table-columns-order",
      JSON.stringify({
        "dataset-a": ["variables.question", "variables.answer"],
      }),
    );

    renderTable("dataset-a");

    expect(headerLabels()).toEqual(["question", "answer"]);
  });
});
