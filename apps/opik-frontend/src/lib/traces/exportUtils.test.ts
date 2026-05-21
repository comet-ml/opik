import { describe, expect, it } from "vitest";

import { Trace } from "@/types/traces";
import { getTagColumnId } from "@/lib/tags";
import { mapRowDataForExport } from "./exportUtils";

describe("mapRowDataForExport", () => {
  it("exports dynamic tag-value columns as Yes or dash", async () => {
    const rows = [
      {
        id: "trace-1",
        tags: ["myprotein-en-gb"],
      },
      {
        id: "trace-2",
        tags: ["other-tag"],
      },
    ] as Trace[];

    await expect(
      mapRowDataForExport(rows, [getTagColumnId("myprotein-en-gb")]),
    ).resolves.toEqual([
      {
        [getTagColumnId("myprotein-en-gb")]: "Yes",
      },
      {
        [getTagColumnId("myprotein-en-gb")]: "-",
      },
    ]);
  });
});
