import { describe, expect, it } from "vitest";
import { chipsToFilters } from "./chipsToFilters";
import { ChipDefinition } from "@/shared/filter-chips/types";

const typeDef: ChipDefinition = {
  id: "type",
  field: "type",
  label: "Type",
  kind: "single-select",
  options: [
    { label: "Thread", value: "thread" },
    { label: "Trace", value: "trace" },
  ],
};

const sourceDef: ChipDefinition = {
  id: "source",
  field: "source",
  label: "Source",
  kind: "single-select",
  options: [
    { label: "UI", value: "ui" },
    { label: "SDK", value: "sdk" },
  ],
};

describe("chipsToFilters", () => {
  it("returns empty array when no values are set", () => {
    expect(chipsToFilters([typeDef], {})).toEqual([]);
  });

  it("skips definitions whose value is undefined", () => {
    const result = chipsToFilters([typeDef, sourceDef], {
      source: { value: "sdk" },
    });
    expect(result).toHaveLength(1);
    expect(result[0].field).toBe("source");
  });

  it("compiles in definition order regardless of values insertion order", () => {
    const result = chipsToFilters([typeDef, sourceDef], {
      source: { value: "sdk" },
      type: { value: "trace" },
    });
    expect(result.map((f) => f.field)).toEqual(["type", "source"]);
  });

  it("drops unapplied chip values (empty string)", () => {
    const result = chipsToFilters([typeDef], { type: { value: "" } });
    expect(result).toEqual([]);
  });
});
