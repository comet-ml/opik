import { describe, it, expect } from "vitest";
import { jsonLanguage } from "@codemirror/lang-json";
import { yamlLanguage } from "@codemirror/lang-yaml";
import { collectQuickFilterTargets } from "./quickFilterPaths";

const json = (doc: string) =>
  collectQuickFilterTargets(jsonLanguage.parser.parse(doc), doc, "json")
    .map(({ path, value }) => ({ path, value }))
    .sort((a, b) => a.path.localeCompare(b.path));

const yaml = (doc: string) =>
  collectQuickFilterTargets(yamlLanguage.parser.parse(doc), doc, "yaml")
    .map(({ path, value }) => ({ path, value }))
    .sort((a, b) => a.path.localeCompare(b.path));

describe("collectQuickFilterTargets - JSON", () => {
  it("maps nested objects, arrays, and scalar types to paths", () => {
    const doc = JSON.stringify({
      git: { branch: "main" },
      providers: ["openai"],
      count: 3,
      enabled: true,
      messages: [{ content: "hi" }],
    });

    expect(json(doc)).toEqual([
      { path: "count", value: "3" },
      { path: "enabled", value: "true" },
      { path: "git.branch", value: "main" },
      { path: "messages[0].content", value: "hi" },
      { path: "providers[0]", value: "openai" },
    ]);
  });

  it("indexes every element of multi-element arrays", () => {
    const doc = JSON.stringify({
      providers: ["openai", "anthropic", "google"],
    });
    expect(json(doc)).toEqual([
      { path: "providers[0]", value: "openai" },
      { path: "providers[1]", value: "anthropic" },
      { path: "providers[2]", value: "google" },
    ]);
  });

  it("emits only value scalars, never object/array key nodes", () => {
    const doc = JSON.stringify({ obj: { x: 1 }, arr: [2], scalar: "v" });
    // obj / arr keys must not appear; only their leaf values and scalar do.
    expect(json(doc)).toEqual([
      { path: "arr[0]", value: "2" },
      { path: "obj.x", value: "1" },
      { path: "scalar", value: "v" },
    ]);
  });

  it("decodes escaped characters in double-quoted values", () => {
    const doc = JSON.stringify({ k: 'a"b\\c' });
    expect(json(doc)).toEqual([{ path: "k", value: 'a"b\\c' }]);
  });

  it("does not offer null-valued attributes (no dead filter)", () => {
    const doc = JSON.stringify({ a: null, b: "x" });
    expect(json(doc)).toEqual([{ path: "b", value: "x" }]);
  });
});

describe("collectQuickFilterTargets - YAML", () => {
  it("maps block mappings and sequences to paths", () => {
    const doc = [
      "git:",
      "  branch: main",
      "providers:",
      "  - openai",
      "count: 3",
    ].join("\n");

    expect(yaml(doc)).toEqual([
      { path: "count", value: "3" },
      { path: "git.branch", value: "main" },
      { path: "providers[0]", value: "openai" },
    ]);
  });

  it("handles the trace metadata shape (providers array + integration)", () => {
    const doc = ["providers:", "  - openai", "integration: manual-test"].join(
      "\n",
    );

    expect(yaml(doc)).toEqual([
      { path: "integration", value: "manual-test" },
      { path: "providers[0]", value: "openai" },
    ]);
  });

  it("indexes every item of a multi-item sequence", () => {
    const doc = [
      "providers:",
      "  - openai",
      "  - anthropic",
      "  - google",
    ].join("\n");
    expect(yaml(doc)).toEqual([
      { path: "providers[0]", value: "openai" },
      { path: "providers[1]", value: "anthropic" },
      { path: "providers[2]", value: "google" },
    ]);
  });

  it("decodes single-quoted YAML escapes (doubled quote)", () => {
    const doc = "note: 'it''s fine'";
    expect(yaml(doc)).toEqual([{ path: "note", value: "it's fine" }]);
  });

  it("skips YAML null scalars (null / ~)", () => {
    const doc = ["a: null", "b: ~", "c: keep"].join("\n");
    expect(yaml(doc)).toEqual([{ path: "c", value: "keep" }]);
  });
});
