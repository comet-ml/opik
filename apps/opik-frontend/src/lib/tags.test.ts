import { describe, expect, it } from "vitest";

import { buildDynamicTagColumns, getTagColumnId } from "./tags";

describe("buildDynamicTagColumns", () => {
  it("builds sorted unique tag columns", () => {
    expect(
      buildDynamicTagColumns(["beta", "alpha", "beta", "", "gamma"]),
    ).toEqual([
      {
        id: getTagColumnId("alpha"),
        label: "alpha",
        columnType: "string",
      },
      {
        id: getTagColumnId("beta"),
        label: "beta",
        columnType: "string",
      },
      {
        id: getTagColumnId("gamma"),
        label: "gamma",
        columnType: "string",
      },
    ]);
  });

  it("preserves the original tag value in the column id and label", () => {
    expect(buildDynamicTagColumns(["myprotein-en-gb"])).toEqual([
      {
        id: "tags.myprotein-en-gb",
        label: "myprotein-en-gb",
        columnType: "string",
      },
    ]);
  });
});
