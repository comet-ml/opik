import { describe, expect, it } from "vitest";

import { extractTagsFromItems, filterTagsByQuery } from "./helpers";

describe("extractTagsFromItems", () => {
  it("returns empty array for null/undefined input", () => {
    expect(extractTagsFromItems(null)).toEqual([]);
    expect(extractTagsFromItems(undefined)).toEqual([]);
  });

  it("returns empty array when no items have tags", () => {
    expect(
      extractTagsFromItems([{}, { tags: null }, { tags: undefined }]),
    ).toEqual([]);
  });

  it("returns empty array for items with empty tag arrays", () => {
    expect(extractTagsFromItems([{ tags: [] }, { tags: [] }])).toEqual([]);
  });

  it("extracts and dedupes tags across items", () => {
    expect(
      extractTagsFromItems([
        { tags: ["alpha", "beta"] },
        { tags: ["beta", "gamma"] },
      ]),
    ).toEqual(["alpha", "beta", "gamma"]);
  });

  it("trims whitespace and skips whitespace-only tags", () => {
    expect(
      extractTagsFromItems([{ tags: ["  alpha  ", "   ", "", "beta"] }]),
    ).toEqual(["alpha", "beta"]);
  });

  it("dedupes tags that differ only in surrounding whitespace", () => {
    expect(
      extractTagsFromItems([{ tags: ["alpha", "  alpha  ", "alpha"] }]),
    ).toEqual(["alpha"]);
  });

  it("preserves case-distinct tags (dedupe is case-sensitive)", () => {
    expect(
      extractTagsFromItems([{ tags: ["Alpha", "alpha", "ALPHA"] }]),
    ).toEqual(["ALPHA", "Alpha", "alpha"]);
  });

  it("returns tags sorted alphabetically", () => {
    expect(extractTagsFromItems([{ tags: ["zeta", "alpha", "mu"] }])).toEqual([
      "alpha",
      "mu",
      "zeta",
    ]);
  });
});

describe("filterTagsByQuery", () => {
  const tags = ["production", "staging", "experiment-1", "PROD-canary"];

  it("returns all tags when query is empty/null/undefined", () => {
    expect(filterTagsByQuery(tags, "")).toEqual(tags);
    expect(filterTagsByQuery(tags, null)).toEqual(tags);
    expect(filterTagsByQuery(tags, undefined)).toEqual(tags);
  });

  it("performs case-insensitive substring match", () => {
    expect(filterTagsByQuery(tags, "prod")).toEqual([
      "production",
      "PROD-canary",
    ]);
    expect(filterTagsByQuery(tags, "PROD")).toEqual([
      "production",
      "PROD-canary",
    ]);
  });

  it("returns empty array when no tags match", () => {
    expect(filterTagsByQuery(tags, "nope")).toEqual([]);
  });

  it("returns a new array (does not mutate input)", () => {
    const result = filterTagsByQuery(tags, "");
    expect(result).not.toBe(tags);
    expect(result).toEqual(tags);
  });
});
