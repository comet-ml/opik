import type { ModelPriceHistoryEntry, ModelPriceRow } from "./types";
import {
  describeChanges,
  describeReplaceResult,
  describeRowPrice,
  groupHistory,
  rowLabel,
} from "./describe";

const GPT_4O: ModelPriceRow = {
  model: "gpt-4o",
  provider: "openai",
  kind: "fixed",
  input_price: "2",
  output_price: "8.5",
};

const ALIAS: ModelPriceRow = {
  model: "bifrost/gpt-4o",
  provider: "",
  kind: "alias",
  alias_of: { model: "gpt-4o", provider: "" },
};

describe("describe", () => {
  it("labels a row by model and provider", () => {
    expect(rowLabel(GPT_4O)).toBe("gpt-4o on openai");
    expect(rowLabel(ALIAS)).toBe("bifrost/gpt-4o on any provider");
  });

  it("describes what a row charges", () => {
    expect(describeRowPrice(GPT_4O)).toBe("input 2 · output 8.5");
    expect(describeRowPrice(ALIAS)).toBe(
      "priced like gpt-4o on the span's own provider",
    );
    expect(describeRowPrice({ model: "x", kind: "fixed" })).toBe(
      "built-in prices",
    );
  });

  it("lists each changed field", () => {
    expect(
      describeChanges(GPT_4O, {
        ...GPT_4O,
        output_price: "8",
        cache_read_price: "1.25",
        note: "new",
      }),
    ).toEqual(["output 8.5 → 8", "cache read built-in → 1.25", "note changed"]);
    expect(describeChanges(GPT_4O, { ...GPT_4O, input_price: "2.00" })).toEqual(
      [],
    );
  });

  it("describes a switch between own prices and an alias", () => {
    expect(
      describeChanges(GPT_4O, {
        ...GPT_4O,
        kind: "alias",
        alias_of: { model: "gpt-4.1", provider: "openai" },
      }),
    ).toEqual(["now priced like gpt-4.1 on openai"]);
    expect(
      describeChanges(ALIAS, { ...ALIAS, kind: "fixed", input_price: "1" }),
    ).toEqual(["now has its own prices: input 1"]);
  });

  it("groups history entries by the version they made, newest first", () => {
    const entry = (
      version: number,
      action: ModelPriceHistoryEntry["action"],
      before: ModelPriceRow | null,
      after: ModelPriceRow | null,
    ): ModelPriceHistoryEntry => ({
      list_version: version,
      row_id: `${version}-${action}`,
      action,
      before,
      after,
      created_at: `2026-11-0${version}T10:00:00Z`,
      created_by: version === 3 ? "maria" : "daniel",
    });

    const versions = groupHistory([
      entry(3, "updated", GPT_4O, { ...GPT_4O, output_price: "8" }),
      entry(3, "deleted", ALIAS, null),
      entry(2, "created", null, ALIAS),
    ]);

    expect(versions).toEqual([
      {
        version: 3,
        createdAt: "2026-11-03T10:00:00Z",
        createdBy: "maria",
        lines: [
          "gpt-4o on openai: output 8.5 → 8",
          "removed bifrost/gpt-4o on any provider",
        ],
      },
      {
        version: 2,
        createdAt: "2026-11-02T10:00:00Z",
        createdBy: "daniel",
        lines: [
          "added bifrost/gpt-4o on any provider: priced like gpt-4o on the span's own provider",
        ],
      },
    ]);
  });

  it("describes a dry-run result", () => {
    expect(
      describeReplaceResult({
        version: 7,
        saved: false,
        created: [ALIAS],
        updated: [{ before: GPT_4O, after: { ...GPT_4O, input_price: "1.9" } }],
        deleted: [{ model: "old", provider: "openai", kind: "fixed" }],
        unchanged: 4,
        warnings: [],
      }),
    ).toEqual({
      created: [
        "bifrost/gpt-4o on any provider: priced like gpt-4o on the span's own provider",
      ],
      updated: ["gpt-4o on openai: input 2 → 1.9"],
      deleted: ["old on openai"],
    });
  });
});
