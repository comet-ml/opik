import {
  buildCatalog,
  builtInLookup,
  extraFields,
  filterCatalog,
  findBuiltIn,
  providersOf,
  sortCatalog,
} from "./catalog";
import {
  blockingWarnings,
  describeBatchProblem,
  describePerToken,
  describeRowLimit,
  overrideFields,
  problemsForRow,
  problemsForRows,
  replaceOne,
  upsertMany,
} from "./single";

const builtIns = [
  {
    model: "gpt-5",
    provider: "openai",
    mode: "chat",
    input_price: "1.25",
    output_price: "10",
    cache_read_price: "0.125",
  },
  { model: "claude-haiku-4-5", provider: "anthropic", input_price: "1" },
];

const rows = [
  {
    id: "r1",
    model: "GPT-5",
    provider: "openai",
    kind: "fixed" as const,
    input_price: "1",
    last_updated_at: "2026-11-01T10:00:00Z",
  },
  {
    id: "r2",
    model: "acme-llama",
    provider: "",
    kind: "fixed" as const,
    input_price: "0.6",
    output_price: "0.6",
  },
  {
    id: "r3",
    model: "gpt-5",
    provider: "bifrost",
    kind: "alias" as const,
    alias_of: { model: "gpt-5", provider: "openai" },
  },
];

describe("buildCatalog", () => {
  const catalog = buildCatalog(builtIns, rows);
  const byModel = (model: string, provider: string) =>
    catalog.find(
      (entry) => entry.model === model && entry.provider === provider,
    )!;

  it("marks an organization row over a built-in model as an override, matching names case-insensitively", () => {
    const entry = byModel("GPT-5", "openai");
    expect(entry.type).toBe("override");
    expect(entry.prices).toEqual({
      input_price: "1",
      output_price: "10",
      cache_read_price: "0.125",
    });
    expect(entry.lastUpdatedAt).toBe("2026-11-01T10:00:00Z");
    expect(
      catalog.filter(
        (item) => item.model === "gpt-5" && item.provider === "openai",
      ),
    ).toEqual([]);
  });

  it("marks rows without a built-in price as custom, and aliases as alias", () => {
    expect(byModel("acme-llama", "").type).toBe("custom");
    expect(byModel("gpt-5", "bifrost").type).toBe("alias");
    expect(byModel("gpt-5", "bifrost").prices).toEqual({});
    expect(byModel("gpt-5", "bifrost").aliasPrices).toEqual(
      byModel("GPT-5", "openai").prices,
    );
  });

  it("shows no alias price when the alias follows each span's provider", () => {
    const [alias] = buildCatalog(
      [],
      [
        {
          model: "x",
          provider: "",
          kind: "alias",
          alias_of: { model: "gpt-5" },
        },
      ],
    );
    expect(alias.aliasPrices).toBeUndefined();
  });

  it("lists built-in models nobody changed as defaults", () => {
    expect(byModel("claude-haiku-4-5", "anthropic").type).toBe("default");
    expect(catalog).toHaveLength(4);
  });

  it("keeps the key of a model when its default price gets overridden", () => {
    const before = buildCatalog(builtIns, []).find(
      (entry) => entry.model === "claude-haiku-4-5",
    )!;
    const after = buildCatalog(builtIns, [
      {
        model: "claude-haiku-4-5",
        provider: "anthropic",
        kind: "fixed" as const,
        input_price: "2",
      },
    ]).find((entry) => entry.model === "claude-haiku-4-5")!;
    expect(before.type).toBe("default");
    expect(after.type).toBe("override");
    expect(after.key).toBe(before.key);
  });

  it("gives an alias its own key next to the built-in of the same name", () => {
    const keys = buildCatalog(builtIns, [
      {
        model: "gpt-5",
        provider: "openai",
        kind: "alias" as const,
        alias_of: { model: "claude-haiku-4-5", provider: "anthropic" },
      },
    ]).map((entry) => entry.key);
    expect(new Set(keys).size).toBe(keys.length);
  });

  describe("a built-in whose name has a provider prefix", () => {
    const azure = [
      {
        model: "azure/gpt-4.1-mini",
        provider: "azure",
        input_price: "0.4",
        output_price: "1.6",
      },
      {
        model: "gpt-4.1-mini",
        provider: "openai",
        input_price: "0.4",
        output_price: "1.6",
      },
    ];
    const defaultKey = buildCatalog(azure, []).find(
      (entry) => entry.provider === "azure",
    )!.key;

    it.each([
      ["the lookup key, as the backend saves it today", "gpt-4.1-mini"],
      ["the name as typed", "azure/gpt-4.1-mini"],
      ["another prefix", "my-gateway/GPT-4.1-mini"],
    ])("shows one override row when the row is saved under %s", (_, model) => {
      const catalog = buildCatalog(azure, [
        { model, provider: "azure", kind: "fixed", input_price: "0.3" },
      ]);
      const onAzure = catalog.filter((entry) => entry.provider === "azure");

      expect(onAzure).toHaveLength(1);
      expect(onAzure[0]).toMatchObject({
        key: defaultKey,
        type: "override",
        prices: { input_price: "0.3", output_price: "1.6" },
      });
      expect(catalog.find((entry) => entry.provider === "openai")?.type).toBe(
        "default",
      );
    });

    it("replaces every built-in that prices the same spans", () => {
      const catalog = buildCatalog(
        [
          { model: "deepseek-chat", provider: "deepseek", input_price: "0.28" },
          {
            model: "deepseek/deepseek-chat",
            provider: "deepseek",
            input_price: "0.28",
          },
        ],
        [
          {
            model: "deepseek-chat",
            provider: "deepseek",
            kind: "fixed",
            input_price: "0.2",
          },
        ],
      );

      expect(catalog.map((entry) => [entry.model, entry.type])).toEqual([
        ["deepseek-chat", "override"],
      ]);
    });

    it("shows the prices an alias follows when its target is saved without the prefix", () => {
      const alias = buildCatalog(azure, [
        {
          model: "team-mini",
          provider: "gateway",
          kind: "alias",
          alias_of: { model: "gpt-4.1-mini", provider: "azure" },
        },
      ]).find((entry) => entry.type === "alias")!;

      expect(alias.aliasPrices).toEqual({
        input_price: "0.4",
        output_price: "1.6",
      });
    });

    it("marks an alias that takes the place of a built-in", () => {
      const catalog = buildCatalog(azure, [
        {
          model: "gpt-4.1-mini",
          provider: "azure",
          kind: "alias",
          alias_of: { model: "gpt-4.1-mini", provider: "openai" },
        },
        {
          model: "team-mini",
          provider: "gateway",
          kind: "alias",
          alias_of: { model: "gpt-4.1-mini", provider: "openai" },
        },
      ]);
      const aliases = catalog.filter((entry) => entry.type === "alias");

      expect(aliases.map((entry) => entry.shadowsBuiltIn)).toEqual([
        true,
        undefined,
      ]);
    });
  });

  it("counts the extra price fields beyond input and output", () => {
    expect(extraFields(byModel("GPT-5", "openai"))).toEqual([
      "cache_read_price",
    ]);
  });

  const models = (entries: { model: string }[]) =>
    entries.map((entry) => entry.model);
  const is = (field: string, value: string) => ({
    field,
    operator: "=",
    value,
  });

  it("filters by search, provider and type, and sorts by model", () => {
    expect(models(filterCatalog(catalog, { search: "LLAMA" }))).toEqual([
      "acme-llama",
    ]);
    expect(
      models(
        filterCatalog(catalog, { filters: [is("provider", "anthropic")] }),
      ),
    ).toEqual(["claude-haiku-4-5"]);
    expect(
      models(filterCatalog(catalog, { filters: [is("type", "Custom")] })),
    ).toEqual(["acme-llama"]);
    expect(
      models(filterCatalog(catalog, { filters: [is("type", "alias")] })),
    ).toEqual(["gpt-5"]);
    expect(
      models(filterCatalog(catalog, { filters: [is("type", "not_default")] })),
    ).not.toContain("claude-haiku-4-5");
    expect(
      filterCatalog(catalog, { filters: [is("type", "Not default")] }),
    ).toHaveLength(3);
    expect(sortCatalog(catalog, "desc")[0].model).toBe("GPT-5");
  });

  it("treats two values of one filter as either, and different filters as both", () => {
    expect(
      models(
        filterCatalog(catalog, {
          filters: [is("provider", "anthropic"), is("provider", "bifrost")],
        }),
      ),
    ).toEqual(["gpt-5", "claude-haiku-4-5"]);
    expect(
      models(
        filterCatalog(catalog, {
          filters: [is("provider", "openai"), is("type", "Default")],
        }),
      ),
    ).toEqual([]);
  });

  it("finds rows without a provider through the Any provider option", () => {
    expect(providersOf(catalog)).toEqual([
      "Any provider",
      "anthropic",
      "bifrost",
      "openai",
    ]);
    expect(
      models(
        filterCatalog(catalog, { filters: [is("provider", "Any provider")] }),
      ),
    ).toEqual(["acme-llama"]);
  });

  it("filters by price per 1M tokens and by having cache prices", () => {
    expect(
      models(
        filterCatalog(catalog, {
          filters: [{ field: "input_price", operator: ">=", value: "1" }],
        }),
      ),
    ).toEqual(["GPT-5", "claude-haiku-4-5"]);
    expect(
      models(
        filterCatalog(catalog, {
          filters: [
            { field: "cache_prices", operator: "is_not_empty", value: "" },
          ],
        }),
      ),
    ).toEqual(["GPT-5"]);
  });

  it("ranks the exact name, then the same name behind a prefix, then names that start with the search", () => {
    const searchable = buildCatalog(
      [
        { model: "azure/gpt-5", provider: "azure", input_price: "1" },
        { model: "gpt-5-mini", provider: "openai", input_price: "1" },
        { model: "gpt-5", provider: "openai", input_price: "1" },
        { model: "my-gpt-5", provider: "x", input_price: "1" },
      ],
      [],
    );
    const found = filterCatalog(searchable, { search: "gpt-5" });
    expect(models(sortCatalog(found, "asc", "gpt-5"))).toEqual([
      "gpt-5",
      "azure/gpt-5",
      "gpt-5-mini",
      "my-gpt-5",
    ]);
  });
});

describe("saving one model", () => {
  const list = { version: 7, rows };

  it("adds a new row at the end and reports its index", () => {
    const save = replaceOne(list, undefined, {
      model: "new",
      provider: "",
      kind: "fixed",
      input_price: "2.00",
    });
    expect(save.index).toBe(3);
    expect(save.request.expected_version).toBe(7);
    expect(save.request.rows[3]).toEqual({
      model: "new",
      provider: "",
      kind: "fixed",
      input_price: "2",
    });
  });

  it("replaces the edited row where it was and removes a restored one", () => {
    const edited = replaceOne(list, rows[1], {
      ...rows[1],
      input_price: "0.5",
    });
    expect(edited.index).toBe(1);
    expect(edited.request.rows).toHaveLength(3);
    expect(edited.request.rows[1].input_price).toBe("0.5");
    const removed = replaceOne(list, rows[0], undefined);
    expect(removed.index).toBe(-1);
    expect(removed.request.rows.map((row) => row.model)).toEqual([
      "acme-llama",
      "gpt-5",
    ]);
  });

  it("keeps only the prices that differ from the built-in ones", () => {
    expect(
      overrideFields(builtIns[0], {
        input_price: "1.250",
        output_price: "8",
        cache_read_price: "",
      }),
    ).toEqual({ output_price: "8" });
  });

  it("upserts uploaded rows by model and provider and says where they landed", () => {
    const { request, positions } = upsertMany(list, [
      { model: "gpt-5", provider: "OpenAI", kind: "fixed", input_price: "0.9" },
      { model: "fresh", provider: "x", kind: "fixed", output_price: "3" },
    ]);
    expect(request.rows).toHaveLength(4);
    expect(request.rows[0].input_price).toBe("0.9");
    expect(request.rows[3].model).toBe("fresh");
    expect(positions).toEqual([0, 3]);
  });

  it("reports a model uploaded twice instead of keeping the last row", () => {
    const { duplicates } = upsertMany(list, [
      { model: "dup", provider: "acme", kind: "fixed", input_price: "1" },
      { model: "fresh", provider: "x", kind: "fixed", input_price: "1" },
      { model: " DUP", provider: "Acme", kind: "fixed", input_price: "3" },
      { model: "dup", provider: "acme", kind: "fixed", input_price: "4" },
    ]);
    expect(duplicates).toEqual([
      "dup on acme appears more than once. Keep one row per model and provider.",
    ]);
  });

  it("explains the row limit with the saved and the new rows", () => {
    const fresh = (count: number) =>
      Array.from({ length: count }, (_, i) => ({
        model: `bulk-${i}`,
        provider: "acme",
        kind: "fixed" as const,
        input_price: "1",
      }));
    const tooMany = upsertMany(list, fresh(998)).request;

    expect(describeRowLimit(list, tooMany)).toBe(
      "The price list can hold at most 1,000 models. With this update it would have 1,001: 3 saved now and 998 new.",
    );
    expect(
      describeRowLimit(list, upsertMany(list, fresh(997)).request),
    ).toBeUndefined();
  });

  it("keeps only the warnings about the uploaded rows or the whole list", () => {
    const warnings = [
      { row: 1, field: "provider", code: "a", message: "existing row" },
      { row: 3, field: "provider", code: "b", message: "uploaded row" },
      { row: null, field: null, code: "c", message: "list" },
    ];
    expect(problemsForRows(warnings, [0, 3]).map((p) => p.message)).toEqual([
      "uploaded row",
      "list",
    ]);
  });

  it("shows the built-in per-token price when a field falls back to it", () => {
    expect(describePerToken("13.1", "20")).toBe("= 0.0000131 / tok");
    expect(describePerToken("", "2.5")).toBe("= 0.0000025 / tok (built-in)");
    expect(describePerToken("", undefined)).toBe("= no price");
  });

  it("shows nothing while the typed price is not a number yet", () => {
    expect(describePerToken("1.2.3", "2.5")).toBe("");
    expect(describePerToken("-1", "2.5")).toBe("");
    expect(describePerToken("abc", undefined)).toBe("");
  });

  it("lets a custom price save without asking about unpriced providers", () => {
    const warnings = [
      { row: 0, field: "provider", code: "provider_unpriced", message: "a" },
      { row: 0, field: null, code: "no_base", message: "b" },
      {
        row: 0,
        field: "input_price",
        code: "price_out_of_range",
        message: "c",
      },
      { row: 0, field: "model", code: "shadows_builtin", message: "d" },
    ];
    expect(blockingWarnings(warnings, true).map((w) => w.code)).toEqual([
      "price_out_of_range",
      "shadows_builtin",
    ]);
    expect(blockingWarnings(warnings, false)).toHaveLength(4);
  });

  it("never asks to confirm a name_normalized note", () => {
    const warnings = [
      { row: 0, field: "model", code: "name_normalized", message: "a" },
      { row: 0, field: "model", code: "shadows_builtin", message: "b" },
    ];
    expect(blockingWarnings(warnings, false).map((w) => w.code)).toEqual([
      "shadows_builtin",
    ]);
    expect(blockingWarnings(warnings, true).map((w) => w.code)).toEqual([
      "shadows_builtin",
    ]);
  });

  it("names the model and the field in a batch problem", () => {
    const uploaded = [
      { model: "gpt-5", provider: "openai", kind: "fixed" as const },
      { model: "acme", provider: "", kind: "fixed" as const },
    ];
    const explain = (problem: object) =>
      describeBatchProblem(
        { row: 0, field: null, code: "x", message: "", ...problem },
        uploaded,
      );

    expect(
      explain({
        field: "input_price",
        code: "price_too_high",
        message: "input_price is over 100000 per 1M tokens",
      }),
    ).toBe(
      "gpt-5 on openai: Input price is over $100,000 per 1M tokens. Prices in the file are per token.",
    );
    expect(
      explain({
        row: 1,
        field: "cache_read_price",
        code: "price_out_of_range",
        message:
          "25 per 1M tokens is far from the built-in 2.5: prices are per 1M tokens, not per token",
      }),
    ).toBe(
      "acme on any provider: Cache read price $25 per 1M tokens is far from the built-in $2.5. Prices in the file are per token.",
    );
    expect(
      explain({
        field: "output_price",
        code: "negative_price",
        message: "output_price can't be negative",
      }),
    ).toBe("gpt-5 on openai: Output price can't be negative");
    expect(
      explain({ field: "model", code: "duplicate_row", message: "Twice" }),
    ).toBe("gpt-5 on openai: Twice");
    expect(
      explain({ row: null, code: "too_many_rows", message: "Too many" }),
    ).toBe("Too many");
  });

  it("keeps the problems of the saved row and of the whole list", () => {
    const problems = [
      { row: 0, field: "input_price", code: "a", message: "first" },
      { row: 2, field: "input_price", code: "b", message: "mine" },
      { row: null, field: null, code: "c", message: "list" },
    ];
    expect(problemsForRow(problems, 2).map((p) => p.message)).toEqual([
      "mine",
      "list",
    ]);
  });
});

describe("builtInLookup", () => {
  it("finds a row's built-in price by model and provider, ignoring case and spaces", () => {
    const first = { model: "gpt-4o", provider: "openai", input_price: "2.5" };
    const later = { model: "GPT-4o", provider: "openai", input_price: "9" };
    const builtInOf = builtInLookup([first, later]);

    expect(
      builtInOf({ model: " GPT-4O ", provider: "OpenAI", kind: "fixed" }),
    ).toBe(first);
    expect(
      builtInOf({ model: "gpt-4o", provider: "azure", kind: "fixed" }),
    ).toBeUndefined();
  });

  it("finds the built-in of a row saved without the name's provider prefix", () => {
    const azure = {
      model: "azure/gpt-4o",
      provider: "azure",
      input_price: "2.5",
    };

    expect(
      builtInLookup([azure])({
        model: "gpt-4o",
        provider: "azure",
        kind: "fixed",
      }),
    ).toBe(azure);
    expect(findBuiltIn([azure], "gpt-4o", "azure")).toBe(azure);
  });
});
