import {
  buildCatalog,
  extraFields,
  filterCatalog,
  providersOf,
  sortCatalog,
} from "./catalog";
import {
  overrideFields,
  problemsForRow,
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

  it("marks rows without a built-in price, including aliases, as custom", () => {
    expect(byModel("acme-llama", "").type).toBe("custom");
    expect(byModel("gpt-5", "bifrost").type).toBe("custom");
    expect(byModel("gpt-5", "bifrost").prices).toEqual({});
  });

  it("lists built-in models nobody changed as defaults", () => {
    expect(byModel("claude-haiku-4-5", "anthropic").type).toBe("default");
    expect(catalog).toHaveLength(4);
  });

  it("counts the extra price fields beyond input and output", () => {
    expect(extraFields(byModel("GPT-5", "openai"))).toEqual([
      "cache_read_price",
    ]);
  });

  it("filters by search, provider and type, and sorts by model", () => {
    expect(
      filterCatalog(catalog, { search: "LLAMA" }).map((entry) => entry.model),
    ).toEqual(["acme-llama"]);
    expect(
      filterCatalog(catalog, { providers: ["anthropic"] }).map(
        (entry) => entry.model,
      ),
    ).toEqual(["claude-haiku-4-5"]);
    expect(
      filterCatalog(catalog, { types: ["custom"] }).map((entry) => entry.model),
    ).toEqual(["acme-llama", "gpt-5"]);
    expect(sortCatalog(catalog, "desc")[0].model).toBe("GPT-5");
    expect(providersOf(catalog)).toEqual(["anthropic", "bifrost", "openai"]);
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

  it("upserts uploaded rows by model and provider", () => {
    const request = upsertMany(list, [
      { model: "gpt-5", provider: "OpenAI", kind: "fixed", input_price: "0.9" },
      { model: "fresh", provider: "x", kind: "fixed", output_price: "3" },
    ]);
    expect(request.rows).toHaveLength(4);
    expect(request.rows[0].input_price).toBe("0.9");
    expect(request.rows[3].model).toBe("fresh");
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
