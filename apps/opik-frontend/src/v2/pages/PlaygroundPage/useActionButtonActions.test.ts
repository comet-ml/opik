import { describe, expect, it } from "vitest";
import { isTestSuiteRun } from "@/v2/pages/PlaygroundPage/useActionButtonActions";
import { DATASET_TYPE } from "@/types/datasets";

describe("isTestSuiteRun", () => {
  it("routes to the test suite path when a test suite is selected", () => {
    expect(isTestSuiteRun("suite-id", DATASET_TYPE.TEST_SUITE)).toBe(true);
  });

  it("ignores a stale test suite type when no dataset is selected", () => {
    expect(isTestSuiteRun(undefined, DATASET_TYPE.TEST_SUITE)).toBe(false);
  });

  it("does not route a regular dataset to the test suite path", () => {
    expect(isTestSuiteRun("dataset-id", DATASET_TYPE.DATASET)).toBe(false);
  });

  it("does not route when there is no dataset type", () => {
    expect(isTestSuiteRun("dataset-id", null)).toBe(false);
  });
});
