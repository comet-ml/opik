import { describe, expect, it } from "vitest";
import { json2csv } from "json-2-csv";
import {
  ModelPriceList,
  buildCatalog,
  isSameRow,
  parsePriceCsv,
  upsertMany,
} from "@/lib/model-prices";
import { toExportRows } from "./exportPrices";

const BUILT_INS = [
  {
    model: "gpt-4o",
    provider: "openai",
    input_price: "2.5",
    output_price: "10",
    cache_read_price: "1.25",
  },
  {
    model: "claude-haiku-4-5",
    provider: "anthropic",
    input_price: "1",
    output_price: "5",
  },
];

const LIST: ModelPriceList = {
  version: 7,
  rows: [
    {
      model: "gpt-4o",
      provider: "openai",
      kind: "fixed",
      input_price: "2",
      note: "Contract rate, until March",
    },
    { model: "acme-llama", provider: "", kind: "fixed", input_price: "0.6" },
    {
      model: "team-model",
      provider: "gateway",
      kind: "alias",
      alias_of: { model: "gpt-4o", provider: "openai" },
      last_updated_at: "2026-11-01T10:00:00Z",
    },
  ],
};

const catalog = buildCatalog(BUILT_INS, LIST.rows);
const rowOf = (model: string) =>
  toExportRows(catalog).find((row) => row.model === model)!;

// The same options ExportToButton writes its CSV with.
const toCsv = (rows: object[]) =>
  json2csv(rows, { arrayIndexesAsKeys: true, escapeHeaderNestedDots: false });

describe("toExportRows", () => {
  it("exports a built-in model's prices per 1M tokens, empty when unset", () => {
    expect(rowOf("claude-haiku-4-5")).toEqual({
      model: "claude-haiku-4-5",
      provider: "anthropic",
      type: "Default",
      priced_like: "",
      input_price_per_1m: "1",
      output_price_per_1m: "5",
      cache_read_price_per_1m: "",
      cache_write_price_per_1m: "",
      note: "",
      last_updated_at: "",
    });
    expect(rowOf("acme-llama").provider).toBe("Any provider");
  });

  it("exports only the prices an override sets itself, and its note", () => {
    expect(rowOf("gpt-4o")).toMatchObject({
      type: "Override",
      input_price_per_1m: "2",
      output_price_per_1m: "",
      cache_read_price_per_1m: "",
      note: "Contract rate, until March",
    });
  });

  it("exports an alias with the prices it follows and what it follows", () => {
    expect(rowOf("team-model")).toMatchObject({
      type: "Alias",
      priced_like: "gpt-4o on openai",
      input_price_per_1m: "2",
      output_price_per_1m: "10",
      last_updated_at: "2026-11-01T10:00:00Z",
    });
  });

  it("keeps the order of the entries it is given", () => {
    expect(
      toExportRows([...catalog].reverse()).map((row) => row.model),
    ).toEqual([...catalog].reverse().map((entry) => entry.model));
  });

  it("changes nothing when the exported file is imported again", () => {
    const upload = parsePriceCsv(toCsv(toExportRows(catalog)));
    const { request } = upsertMany(LIST, upload.rows);

    expect(upload.errors).toEqual([]);
    expect(upload.notes).toEqual([
      "Skipped 1 row of type Default: it uses the built-in price. To set your own price, change the type to Override.",
    ]);
    expect(request.rows).toHaveLength(LIST.rows.length);
    request.rows.forEach((row, index) =>
      expect(isSameRow(row, LIST.rows[index])).toBe(true),
    );
  });
});
