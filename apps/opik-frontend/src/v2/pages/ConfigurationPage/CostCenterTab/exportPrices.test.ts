import { describe, expect, it } from "vitest";
import { buildCatalog } from "@/lib/model-prices";
import { toExportRows } from "./exportPrices";

const catalog = buildCatalog(
  [
    {
      model: "gpt-4o",
      provider: "openai",
      input_price: "2.5",
      output_price: "10",
      cache_read_price: "1.25",
    },
  ],
  [
    { model: "acme-llama", provider: "", kind: "fixed", input_price: "0.6" },
    {
      model: "team-model",
      provider: "gateway",
      kind: "alias",
      alias_of: { model: "gpt-4o", provider: "openai" },
      last_updated_at: "2026-11-01T10:00:00Z",
    },
  ],
);
const rowOf = (model: string) =>
  toExportRows(catalog).find((row) => row.model === model)!;

describe("toExportRows", () => {
  it("exports every price field per 1M tokens, empty when unset", () => {
    expect(rowOf("gpt-4o")).toEqual({
      model: "gpt-4o",
      provider: "openai",
      type: "Default",
      priced_like: "",
      input_price_per_1m: "2.5",
      output_price_per_1m: "10",
      cache_read_price_per_1m: "1.25",
      cache_write_price_per_1m: "",
      last_updated_at: "",
    });
    expect(rowOf("acme-llama").provider).toBe("Any provider");
  });

  it("exports an alias with the prices it follows and what it follows", () => {
    expect(rowOf("team-model")).toMatchObject({
      type: "Alias",
      priced_like: "gpt-4o on openai",
      input_price_per_1m: "2.5",
      output_price_per_1m: "10",
      last_updated_at: "2026-11-01T10:00:00Z",
    });
  });

  it("keeps the order of the entries it is given", () => {
    expect(
      toExportRows([...catalog].reverse()).map((row) => row.model),
    ).toEqual([...catalog].reverse().map((entry) => entry.model));
  });
});
