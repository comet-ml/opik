import { afterEach, describe, expect, it, vi } from "vitest";
import { loader } from "vega";
import { coerceNumericRows, prepareVegaSpec, withoutNetwork } from "./vega";

describe("withoutNetwork", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it.each([
    ["an external url", "https://attacker.example/collect"],
    [
      "a same-origin API read",
      "/opik/api/v1/private/projects?workspace_name=other",
    ],
  ])("refuses to load %s without fetching it", async (_, uri) => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");

    await expect(withoutNetwork(loader()).load(uri)).rejects.toThrow();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it.each(["image", "href"] as const)("refuses an %s url", async (context) => {
    await expect(
      withoutNetwork(loader()).sanitize("https://attacker.example/", {
        context,
      }),
    ).rejects.toThrow();
  });
});

describe("prepareVegaSpec", () => {
  it("drops usermeta, so a stored spec cannot override the embed options", () => {
    const prepared = prepareVegaSpec(
      {
        spec: {
          mark: "bar",
          data: { name: "rows" },
          usermeta: { embedOptions: { ast: false, patch: [] } },
        },
        rows: [{ x: "a", y: 1 }],
      },
      {},
    );

    expect(prepared).not.toHaveProperty("usermeta");
  });

  const colorScale = (prepared: Record<string, unknown>) =>
    (prepared.encoding as { color: { scale?: { domain: unknown[] } } }).color
      .scale;

  it("keeps the colour domain the same type as the coerced data", () => {
    const prepared = prepareVegaSpec(
      {
        spec: {
          mark: "bar",
          data: { name: "rows" },
          encoding: { color: { field: "code", type: "nominal" } },
        },
        rows: [
          { code: "200", n: "1" },
          { code: "500", n: "2" },
        ],
      },
      {},
    );

    expect(colorScale(prepared)?.domain).toEqual([200, 500]);
  });

  it.each([
    ["the unit", { transform: [{ filter: "datum.n > 1" }] }, undefined],
    [
      "the parent of a layer",
      undefined,
      { transform: [{ filter: "datum.n > 1" }] },
    ],
  ])(
    "leaves the colour domain to Vega when %s has a transform",
    (_, unitExtra, rootExtra) => {
      const unit = {
        mark: "bar",
        encoding: { color: { field: "code", type: "nominal" } },
        ...unitExtra,
      };
      const prepared = prepareVegaSpec(
        {
          spec: rootExtra
            ? { data: { name: "rows" }, layer: [unit], ...rootExtra }
            : { data: { name: "rows" }, ...unit },
          rows: [{ code: "a", n: "1" }],
        },
        {},
      );

      const target = rootExtra
        ? (prepared.layer as Record<string, unknown>[])[0]
        : prepared;
      expect(colorScale(target)).toBeUndefined();
    },
  );

  it("binds live rows even when the spec carries its own values", () => {
    const prepared = prepareVegaSpec(
      {
        spec: { mark: "bar", data: { values: [{ x: "stale" }] } },
        rows: [{ x: "live" }],
      },
      {},
    );

    expect(prepared.data).toEqual({ name: "rows" });
    expect(prepared.datasets).toEqual({ rows: [{ x: "live" }] });
  });
});

describe("coerceNumericRows", () => {
  it("turns numeric strings into numbers but keeps codes and unsafe integers as text", () => {
    const [row] = coerceNumericRows([
      {
        count: "12",
        ratio: "0.5",
        scientific: "1e3",
        negative: "-4",
        code: "00123",
        id: "9007199254740993",
        name: "gpt-4",
      },
    ]);

    expect(row).toEqual({
      count: 12,
      ratio: 0.5,
      scientific: 1000,
      negative: -4,
      code: "00123",
      id: "9007199254740993",
      name: "gpt-4",
    });
  });
});
