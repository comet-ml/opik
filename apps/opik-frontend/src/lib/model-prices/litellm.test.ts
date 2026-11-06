import {
  parseBuiltInPrices,
  parseLiteLlmUpload,
  perMillionToPerToken,
  perTokenToPerMillion,
} from "./litellm";

describe("price units", () => {
  it("turns dollars per token into dollars per 1M without float noise", () => {
    expect(perTokenToPerMillion(3e-6)).toBe("3");
    expect(perTokenToPerMillion(1.25e-6)).toBe("1.25");
    expect(perTokenToPerMillion(1.25e-7)).toBe("0.125");
    expect(perTokenToPerMillion(0)).toBe("0");
  });

  it("turns dollars per 1M back into dollars per token exactly", () => {
    expect(perMillionToPerToken("13.1")).toBe("0.0000131");
    expect(perMillionToPerToken("62.08")).toBe("0.00006208");
    expect(perMillionToPerToken("1500000")).toBe("1.5");
    expect(perMillionToPerToken("")).toBe("");
  });

  it("gives no per-token price for input that is not a positive number", () => {
    expect(perMillionToPerToken("1.2.3")).toBe("");
    expect(perMillionToPerToken("abc")).toBe("");
    expect(perMillionToPerToken("-5")).toBe("");
  });
});

describe("parseBuiltInPrices", () => {
  it("keeps token-priced models with their provider, mode and per-1M prices", () => {
    const prices = parseBuiltInPrices({
      sample_spec: { input_cost_per_token: 1 },
      "gpt-5": {
        litellm_provider: "openai",
        mode: "chat",
        input_cost_per_token: 1.25e-6,
        output_cost_per_token: 1e-5,
        cache_read_input_token_cost: 1.25e-7,
      },
      "dall-e-3": { litellm_provider: "openai", output_cost_per_image: 0.04 },
    });
    expect(prices).toEqual([
      {
        model: "gpt-5",
        provider: "openai",
        mode: "chat",
        input_price: "1.25",
        output_price: "10",
        cache_read_price: "0.125",
      },
    ]);
  });
});

describe("parseLiteLlmUpload", () => {
  it("reads LiteLLM-format prices into organization rows", () => {
    const upload = parseLiteLlmUpload(
      JSON.stringify({
        "gpt-5": {
          litellm_provider: "openai",
          input_cost_per_token: 0.000001,
          output_cost_per_token: 0.00001,
          cache_read_input_token_cost: 0.000000125,
        },
        "acme-finetune-v3": {
          input_cost_per_token: 0.000002,
          note: "Contract 2026",
        },
      }),
    );
    expect(upload.errors).toEqual([]);
    expect(upload.rows).toEqual([
      {
        model: "gpt-5",
        provider: "openai",
        kind: "fixed",
        input_price: "1",
        output_price: "10",
        cache_read_price: "0.125",
      },
      {
        model: "acme-finetune-v3",
        provider: "",
        kind: "fixed",
        input_price: "2",
        note: "Contract 2026",
      },
    ]);
  });

  it("explains broken JSON, wrong shapes and bad prices", () => {
    expect(parseLiteLlmUpload("{").errors[0]).toMatch(/valid JSON/);
    expect(parseLiteLlmUpload("[]").errors[0]).toMatch(/object of models/);
    const upload = parseLiteLlmUpload(
      JSON.stringify({
        a: { input_cost_per_token: -1 },
        b: { litellm_provider: "x" },
        c: 3,
      }),
    );
    expect(upload.rows).toEqual([]);
    expect(upload.errors).toEqual([
      "a: input_cost_per_token must be a number of dollars per token, 0 or more.",
      "a: no price fields found.",
      "b: no price fields found.",
      "c: expected an object of prices.",
    ]);
  });
});
