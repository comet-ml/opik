import { describe, expect, it } from "vitest";

import { DEFAULT_OPERATOR_MAP, OPERATORS_MAP } from "@/constants/filters";
import { COLUMN_TYPE } from "@/types/shared";

describe("filter constants", () => {
  it("keeps errors filters defaulting to non-empty while supporting text search", () => {
    expect(DEFAULT_OPERATOR_MAP[COLUMN_TYPE.errors]).toBe("is_not_empty");
    expect(OPERATORS_MAP[COLUMN_TYPE.errors].map(({ value }) => value)).toEqual(
      ["is_empty", "is_not_empty", "contains", "not_contains"],
    );
  });
});
