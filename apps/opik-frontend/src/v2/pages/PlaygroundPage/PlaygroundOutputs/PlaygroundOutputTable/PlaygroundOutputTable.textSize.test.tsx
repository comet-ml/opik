import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import PlaygroundOutputTable from "./PlaygroundOutputTable";

vi.mock("@/v2/pages/PlaygroundPage/useIncrementalDatasetHydration", () => ({
  useIncrementalDatasetHydration: <T,>(items: T[]) => items,
}));
vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundOutputColumnHeader",
  () => ({ default: () => "Prompt A" }),
);
vi.mock(
  "@/v2/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundOutputCell",
  () => ({ default: () => null }),
);

const XS_TEXT_CLASSES = [
  "[&_[data-cell-wrapper]]:text-xs",
  "[&_[data-header-wrapper]]:text-xs",
];

const tableRoot = (testId: string) =>
  screen.getByTestId(`${testId}-header`).parentElement;

describe("PlaygroundOutputTable text size", () => {
  it("should use xs text in the dataset table and leave the outputs table as it is", () => {
    render(
      <TooltipProvider>
        <PlaygroundOutputTable
          datasetId="dataset-a"
          datasetItems={[]}
          datasetColumns={[{ name: "question", types: [] }]}
          promptIds={["prompt-a"]}
          isLoadingDatasetItems={false}
          isFetchingData={false}
        />
      </TooltipProvider>,
    );

    expect(
      screen
        .getByTestId("playground-variables-table-header")
        .querySelector("[data-header-wrapper]"),
    ).not.toBeNull();
    expect(tableRoot("playground-variables-table")).toHaveClass(
      ...XS_TEXT_CLASSES,
    );
    expect(tableRoot("playground-outputs-table")).not.toHaveClass(
      ...XS_TEXT_CLASSES,
    );
  });
});
