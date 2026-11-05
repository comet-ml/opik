import { describe, expect, it } from "vitest";

import { EVALUATION_METHOD } from "@/types/datasets";
import { getEvaluationMethodFilter } from "./GeneralDatasetsTab";

describe("getEvaluationMethodFilter", () => {
  it("scopes the Test suites tab to test suite experiments", () => {
    expect(
      getEvaluationMethodFilter(EVALUATION_METHOD.TEST_SUITE),
    ).toMatchObject({
      field: "evaluation_method",
      operator: "=",
      value: EVALUATION_METHOD.TEST_SUITE,
    });
  });

  it("scopes the Datasets tab to everything that is not a test suite, so legacy experiments stay visible", () => {
    expect(getEvaluationMethodFilter(EVALUATION_METHOD.DATASET)).toMatchObject({
      field: "evaluation_method",
      operator: "!=",
      value: EVALUATION_METHOD.TEST_SUITE,
    });
  });
});
