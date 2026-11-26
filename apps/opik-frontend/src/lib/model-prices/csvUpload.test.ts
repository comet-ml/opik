import { parsePriceCsv, parsePriceUpload } from "./csvUpload";

const HEADER =
  "model,provider,type,priced_like,input_price_per_1m,output_price_per_1m,cache_read_price_per_1m,cache_write_price_per_1m,last_updated_at";

describe("parsePriceCsv", () => {
  it("reads back the rows Export as CSV writes", () => {
    const upload = parsePriceCsv(
      [
        HEADER,
        "gpt-4o,openai,Override,,2,8,1,,2026-11-25T10:00:00Z",
        "acme-llama,Any provider,Custom,,0.6,0.6,,,",
        "team-model,gateway,Alias,gpt-4o on openai,2,8,1,,",
        "bifrost/gpt-4o,Any provider,Alias,gpt-4o on the span's own provider,,,,,",
      ].join("\n"),
    );

    expect(upload.errors).toEqual([]);
    expect(upload.rows).toEqual([
      {
        model: "gpt-4o",
        provider: "openai",
        kind: "fixed",
        input_price: "2",
        output_price: "8",
        cache_read_price: "1",
      },
      {
        model: "acme-llama",
        provider: "",
        kind: "fixed",
        input_price: "0.6",
        output_price: "0.6",
      },
      {
        model: "team-model",
        provider: "gateway",
        kind: "alias",
        alias_of: { model: "gpt-4o", provider: "openai" },
      },
      {
        model: "bifrost/gpt-4o",
        provider: "",
        kind: "alias",
        alias_of: { model: "gpt-4o" },
      },
    ]);
  });

  it("skips the built-in rows of an export instead of saving them as the organization's", () => {
    const upload = parsePriceCsv(
      [
        HEADER + ",note",
        "gpt-4o,openai,Override,,2,,,,,Contract rate",
        "claude-haiku-4-5,anthropic,Default,,1,5,,,,",
        "gpt-4o-mini,openai,default,,0.15,0.6,,,,",
      ].join("\n"),
    );

    expect(upload.errors).toEqual([]);
    expect(upload.rows).toEqual([
      {
        model: "gpt-4o",
        provider: "openai",
        kind: "fixed",
        input_price: "2",
        note: "Contract rate",
      },
    ]);
    expect(upload.notes).toEqual([
      "Skipped 2 rows of type Default: they use the built-in price. To set your own price, change the type to Override.",
    ]);
  });

  it("keeps tiny prices exact instead of turning them into 1e-7", () => {
    const upload = parsePriceCsv(
      "model,provider,input_price_per_1m\ncheap,acme,0.0000001",
    );

    expect(upload.rows[0].input_price).toBe("0.0000001");
  });

  it("explains rows it can't use", () => {
    const upload = parsePriceCsv(
      [
        "model,provider,input_price_per_1m",
        "gpt-4o,openai,$2.50",
        ",openai,1",
        "nothing,openai,",
      ].join("\n"),
    );

    expect(upload.rows).toEqual([]);
    expect(upload.errors).toEqual([
      "gpt-4o: input_price_per_1m must be a number of dollars per 1M tokens, 0 or more.",
      "gpt-4o: no price columns found.",
      "Line 3: model is empty.",
      "nothing: no price columns found.",
    ]);
  });

  it("reads quoted fields with commas, quotes and line breaks", () => {
    const upload = parsePriceCsv(
      'model,provider,input_price_per_1m,note\r\n"acme, large",acme,1,"Contract ""A"",\nsigned"',
    );

    expect(upload.rows).toEqual([
      {
        model: "acme, large",
        provider: "acme",
        kind: "fixed",
        input_price: "1",
        note: 'Contract "A",\nsigned',
      },
    ]);
  });

  it("says when a quote is never closed", () => {
    expect(parsePriceCsv('model,input_price_per_1m\n"gpt-4o,1').errors).toEqual(
      ["This isn't valid CSV. A quote is opened but never closed."],
    );
  });

  it("asks for a model column", () => {
    expect(parsePriceCsv("name,price\ngpt-4o,2").errors).toEqual([
      'Expected a header row with a "model" column, like the one Export as CSV writes.',
    ]);
  });
});

describe("parsePriceUpload", () => {
  it("reads JSON in LiteLLM's format and anything else as CSV", () => {
    expect(
      parsePriceUpload('{ "gpt-5": { "input_cost_per_token": 0.000001 } }')
        .rows[0],
    ).toMatchObject({ model: "gpt-5", input_price: "1" });
    expect(
      parsePriceUpload("model,input_price_per_1m\ngpt-5,1").rows[0],
    ).toMatchObject({ model: "gpt-5", input_price: "1" });
  });
});
