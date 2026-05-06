import { describe, it, expect } from "vitest";
import { selectDefaultType } from "./selectDefaultType";
import { DATASET_TYPE } from "@/types/datasets";

describe("selectDefaultType", () => {
  it("returns Dataset when only datasets exist", () => {
    expect(selectDefaultType({ hasDatasets: true, hasTestSuites: false })).toBe(
      DATASET_TYPE.DATASET,
    );
  });

  it("returns Test suite when only test suites exist", () => {
    expect(selectDefaultType({ hasDatasets: false, hasTestSuites: true })).toBe(
      DATASET_TYPE.TEST_SUITE,
    );
  });

  it("returns Dataset when both exist", () => {
    expect(selectDefaultType({ hasDatasets: true, hasTestSuites: true })).toBe(
      DATASET_TYPE.DATASET,
    );
  });

  it("returns Dataset when neither exists", () => {
    expect(
      selectDefaultType({ hasDatasets: false, hasTestSuites: false }),
    ).toBe(DATASET_TYPE.DATASET);
  });

  it("respects currentDatasetType when provided (edit mode)", () => {
    expect(
      selectDefaultType({
        hasDatasets: true,
        hasTestSuites: false,
        currentDatasetType: DATASET_TYPE.TEST_SUITE,
      }),
    ).toBe(DATASET_TYPE.TEST_SUITE);

    expect(
      selectDefaultType({
        hasDatasets: false,
        hasTestSuites: true,
        currentDatasetType: DATASET_TYPE.DATASET,
      }),
    ).toBe(DATASET_TYPE.DATASET);
  });
});
