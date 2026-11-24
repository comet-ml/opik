import {
  costsMatch,
  describeMismatch,
  describePriceType,
  describeVersionChange,
  freshnessOf,
  groupSpanCosts,
  needsBuiltInPrices,
  parseCostVersion,
  priceGroup,
  priceSpan,
  priceTypeOf,
  spanCostOrigin,
  usageRequestOf,
  visibleLines,
} from "./costBreakdown";
import type { CostedSpan } from "./costBreakdown";
import type { ModelPriceResolution } from "./types";

const SDK_USAGE = {
  total_tokens: 3000,
  completion_tokens: 2000,
  prompt_tokens: 1000,
  "original_usage.total_tokens": 3000,
  "original_usage.completion_tokens": 2000,
  "original_usage.prompt_tokens": 1000,
};

// The local test environment's answer for gpt-4o-mini, overridden to input 0 and output 1 at list version 6.
const OVERRIDE: ModelPriceResolution = {
  model: "gpt-4o-mini",
  provider: "openai",
  cost: "0.002",
  cost_source: "organization",
  list_version: 6,
  match: {
    provider: "openai",
    name_form: "exact",
    row: {
      model: "gpt-4o-mini",
      provider: "openai",
      kind: "fixed",
      input_price: "0",
      output_price: "1",
      cache_read_price: "1",
    },
  },
  lines: [
    { name: "input", rate_per_million: "0", source: "organization" },
    { name: "output", rate_per_million: "1", source: "organization" },
    { name: "cache_read", rate_per_million: "1", source: "organization" },
    { name: "cache_write", rate_per_million: "0", source: "none" },
  ],
};

const BUILT_IN: ModelPriceResolution = {
  model: "gpt-4o-mini",
  provider: "openai",
  cost: "0.00135",
  cost_source: "built_in",
  list_version: 0,
  match: { provider: "openai", name_form: "exact" },
  lines: [
    { name: "input", rate_per_million: "0.15", source: "built_in" },
    { name: "output", rate_per_million: "0.6", source: "built_in" },
    { name: "cache_read", rate_per_million: "0.075", source: "built_in" },
    { name: "cache_write", rate_per_million: "0", source: "none" },
  ],
};

const span = (overrides: Partial<CostedSpan> = {}): CostedSpan => ({
  model: "gpt-4o-mini",
  provider: "openai",
  usage: SDK_USAGE,
  total_estimated_cost: 0.002,
  total_estimated_cost_version: "1.1;o6",
  ...overrides,
});

describe("parseCostVersion", () => {
  it("reads where an estimated cost's prices came from", () => {
    expect(parseCostVersion("1.1;o6")).toEqual({
      kind: "organization",
      listVersion: 6,
    });
    expect(parseCostVersion("1.1;b79c87f5c")).toEqual({ kind: "built_in" });
    expect(parseCostVersion("1.1;fb")).toEqual({ kind: "load_failed" });
    expect(parseCostVersion("1.1")).toEqual({ kind: "unrecorded" });
    expect(parseCostVersion("")).toEqual({ kind: "client" });
    expect(parseCostVersion(null)).toEqual({ kind: "client" });
  });
});

describe("spanCostOrigin", () => {
  it("treats a plain version as sent by the app only when metadata.cost matches", () => {
    const metadata = { cost: { currency: "USD", total_cost: 0.5 } };

    expect(
      spanCostOrigin(
        span({
          total_estimated_cost: 0.5,
          total_estimated_cost_version: "1.1",
          metadata,
        }),
      ),
    ).toEqual({ kind: "client" });
    expect(
      spanCostOrigin(
        span({
          total_estimated_cost: 0.0004,
          total_estimated_cost_version: "1.1",
          metadata,
        }),
      ),
    ).toEqual({ kind: "unrecorded" });
    expect(
      spanCostOrigin(span({ total_estimated_cost_version: "1.1" })),
    ).toEqual({ kind: "unrecorded" });
  });
});

describe("costsMatch", () => {
  it("allows rounding but not a different price", () => {
    expect(costsMatch(0.0000108, 90 * 0.12 * 1e-6)).toBe(true);
    expect(costsMatch(1e-13, 0)).toBe(true);
    expect(costsMatch(0.011, 1.1)).toBe(false);
  });
});

describe("freshnessOf", () => {
  it("is current when today's prices give the billed cost, whatever the list version", () => {
    expect(
      freshnessOf({ kind: "organization", listVersion: 4 }, 0.002, 0.002),
    ).toEqual({ status: "current", fromVersions: [] });
  });

  it("names the list a changed price came from", () => {
    expect(
      freshnessOf({ kind: "organization", listVersion: 4 }, 0.011, 1.1),
    ).toEqual({ status: "changed", fromVersions: [4] });
    expect(freshnessOf({ kind: "built_in" }, 0.00045, 0.002)).toEqual({
      status: "changed",
      fromVersions: [],
    });
  });

  it("can't say what changed when the prices used weren't recorded", () => {
    expect(freshnessOf({ kind: "unrecorded" }, 0.00045, 0.002)).toEqual({
      status: "unrecorded",
      fromVersions: [],
    });
  });
});

describe("describeVersionChange", () => {
  it("says which price lists the cost moved between", () => {
    expect(describeVersionChange([4], OVERRIDE)).toBe("price list v4 → v6");
    expect(describeVersionChange([3, 4, 3], OVERRIDE)).toBe(
      "price lists v3, v4 → v6",
    );
    expect(describeVersionChange([], OVERRIDE)).toBe(
      "built-in prices → price list v6",
    );
    expect(describeVersionChange([4], BUILT_IN)).toBe(
      "price list v4 → built-in prices",
    );
    expect(
      describeVersionChange([4], { cost_source: "none", list_version: 6 }),
    ).toBe("price list v4 → no price");
  });

  it("says nothing when the lists are the same", () => {
    expect(describeVersionChange([6], OVERRIDE)).toBe("");
    expect(describeVersionChange([], BUILT_IN)).toBe("");
  });
});

describe("priceSpan", () => {
  it("adds up to the cost the backend gives for the same usage", () => {
    const breakdown = priceSpan(span(), OVERRIDE);

    expect(visibleLines(breakdown.lines)).toEqual([
      {
        name: "input",
        tokens: 1000,
        source: "prompt_tokens",
        ratePerMillion: "0",
        usesInputRate: false,
        cost: 0,
      },
      {
        name: "output",
        tokens: 2000,
        source: "completion_tokens",
        ratePerMillion: "1",
        usesInputRate: false,
        cost: 0.002,
      },
    ]);
    expect(breakdown.addsUp).toBe(true);
    expect(breakdown.freshness.status).toBe("current");
  });

  it("bills cache reads at the input rate when the model has no cache-read rate", () => {
    const breakdown = priceSpan(
      span({
        usage: {
          prompt_tokens: 1000,
          completion_tokens: 10,
          "prompt_tokens_details.cached_tokens": 400,
        },
      }),
      {
        ...BUILT_IN,
        lines: BUILT_IN.lines.map((line) =>
          line.name === "cache_read"
            ? { ...line, rate_per_million: "0", source: "none" }
            : line,
        ),
      },
    );

    expect(breakdown.lines[1]).toMatchObject({
      name: "cache_read",
      tokens: 400,
      ratePerMillion: "0.15",
      usesInputRate: true,
    });
  });

  it("notices when its lines don't add up to the backend's cost", () => {
    const breakdown = priceSpan(span(), { ...OVERRIDE, cost: "0.0025" });

    expect(breakdown.addsUp).toBe(false);
  });

  it("compares the billed cost with today's", () => {
    const breakdown = priceSpan(
      span({
        total_estimated_cost: 0.011,
        total_estimated_cost_version: "1.1;o4",
      }),
      OVERRIDE,
    );

    expect(breakdown.billed).toBe(0.011);
    expect(breakdown.today).toBe(0.002);
    expect(breakdown.freshness).toEqual({
      status: "changed",
      fromVersions: [4],
    });
  });
});

describe("priceTypeOf", () => {
  const builtIns = [{ model: "gpt-4o-mini", provider: "openai" }];

  it("labels the price the way the Cost center does", () => {
    expect(priceTypeOf(OVERRIDE, builtIns)).toEqual({
      kind: "catalog",
      type: "override",
    });
    expect(priceTypeOf(OVERRIDE, [])).toEqual({
      kind: "catalog",
      type: "custom",
    });
    expect(priceTypeOf(BUILT_IN)).toEqual({ kind: "catalog", type: "default" });
    expect(
      priceTypeOf({ ...OVERRIDE, match: undefined, cost_source: "none" }),
    ).toEqual({ kind: "none" });
  });

  it("names an alias's target", () => {
    const alias = {
      ...OVERRIDE,
      match: {
        provider: "",
        name_form: "exact",
        row: {
          model: "custom-alias",
          provider: "",
          kind: "alias" as const,
          alias_of: { model: "custom", provider: "" },
        },
      },
    };

    expect(needsBuiltInPrices(alias)).toBe(false);
    expect(describePriceType(priceTypeOf(alias)!)).toBe("Same as custom");
  });

  it("waits for the built-in prices before telling an override from a custom price", () => {
    expect(needsBuiltInPrices(OVERRIDE)).toBe(true);
    expect(priceTypeOf(OVERRIDE)).toBeUndefined();
  });
});

describe("describeMismatch", () => {
  it("names the tokens that may bill at other rates", () => {
    expect(describeMismatch(["audio", "reasoning", "audio"])).toBe(
      "The breakdown may not add up exactly: this model may bill audio tokens or reasoning tokens at rates not shown here.",
    );
    expect(describeMismatch([])).toBe(
      "The breakdown may not add up exactly: this model may bill some tokens at rates not shown here.",
    );
  });
});

describe("groupSpanCosts", () => {
  it("groups estimated costs by model and keeps the app's own costs apart", () => {
    const result = groupSpanCosts([
      span(),
      span({ total_estimated_cost: 0.003 }),
      span({
        model: "claude-haiku-4-5",
        provider: "anthropic",
        usage: { input_tokens: 10, output_tokens: 5 },
        total_estimated_cost: 0.01,
        total_estimated_cost_version: "1.1;b79c87f5c",
      }),
      span({ total_estimated_cost: 0.2, total_estimated_cost_version: "" }),
      span({ total_estimated_cost: null }),
    ]);

    expect(
      result.groups.map(({ model, provider, spans, cost }) => ({
        model,
        provider,
        spans: spans.length,
        cost,
      })),
    ).toEqual([
      {
        model: "claude-haiku-4-5",
        provider: "anthropic",
        spans: 1,
        cost: 0.01,
      },
      { model: "gpt-4o-mini", provider: "openai", spans: 2, cost: 0.005 },
    ]);
    expect(result.groups[1].inputTokens).toBe(2000);
    expect(result.groups[1].outputTokens).toBe(4000);
    expect(result.sentByApp).toEqual({ spans: 1, cost: 0.2 });
    expect(result.total).toBeCloseTo(0.215, 12);
  });

  it("falls back to the model named in the metadata", () => {
    const result = groupSpanCosts([
      span({ model: "", metadata: { model: "gpt-4o" } }),
    ]);

    expect(result.groups[0].model).toBe("gpt-4o");
  });
});

describe("priceGroup", () => {
  it("adds the field lines of every span and tells changed prices from lines that don't add up", () => {
    const [group] = groupSpanCosts([
      span(),
      span({
        total_estimated_cost: 0.011,
        total_estimated_cost_version: "1.1;o4",
      }),
      span({ total_estimated_cost: 0.009 }),
    ]).groups;

    const breakdown = priceGroup(group, OVERRIDE);

    expect(visibleLines(breakdown.lines).map((line) => line.tokens)).toEqual([
      3000, 6000,
    ]);
    expect(breakdown.today).toBeCloseTo(0.006, 12);
    expect(breakdown.billed).toBeCloseTo(0.022, 12);
    expect(breakdown.changedSpans).toBe(1);
    expect(breakdown.unexplainedSpans).toBe(1);
    expect(breakdown.fromVersions).toEqual([4]);
  });
});

describe("usageRequestOf", () => {
  it("keeps the whole-number counts the price explanation accepts", () => {
    expect(
      usageRequestOf({
        prompt_tokens: 90,
        completion_tokens: 1,
        ratio: 0.5,
        label: "x",
      }),
    ).toEqual({ prompt_tokens: 90, completion_tokens: 1 });
    expect(usageRequestOf(undefined)).toEqual({});
  });
});
