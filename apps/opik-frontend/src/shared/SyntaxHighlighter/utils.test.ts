import { describe, it, expect } from "vitest";
import { parse } from "yaml";
import { generateSyntaxHighlighterCode } from "./utils";
import { MODE_TYPE } from "./constants";
import { PrettifyConfig } from "./types";

const NBSP = "\u00A0";
const LINE_SEPARATOR = "\u2028";
const DEL = "\u007F";

const generate = (
  data: object,
  mode: MODE_TYPE = MODE_TYPE.yaml,
  prettifyConfig?: PrettifyConfig,
) => generateSyntaxHighlighterCode(data, mode, prettifyConfig);

const toYaml = (data: object) => generate(data).message;

describe("generateSyntaxHighlighterCode - YAML mode", () => {
  it("renders multiline strings as block scalars", () => {
    expect(toYaml({ arg: "line1\nline2" })).toBe("arg: |-\n  line1\n  line2");
  });

  it.each([
    ["non-breaking space", NBSP],
    ["line separator", LINE_SEPARATOR],
    ["emoji", "\u{1F600}"],
    ["CJK", "中文"],
  ])(
    "keeps block style for multiline strings containing %s",
    (_label, char) => {
      const value = `role:${char}assistant\ninstructions:\n  - cite sources`;

      const result = toYaml({ arg: value });

      expect(result).toContain("|-");
      expect(result).not.toContain("\\n");
      expect(parse(result).arg).toBe(value);
    },
  );

  it("escapes characters YAML does not allow unquoted, keeping output parseable", () => {
    const value = `line1${DEL}x\nline2`;

    const result = toYaml({ arg: value });

    expect(result).not.toContain("|-");
    expect(parse(result).arg).toBe(value);
  });

  it.each([
    ["DEL", "\u007F", "\\x7F"],
    ["NEL", "\u0085", "\\N"],
    ["a C1 control", "\u0080", "\\x80"],
  ])("escapes %s inside the double-quoted value", (_label, char, escape) => {
    const value = `line1${char}x\nline2`;

    const result = toYaml({ arg: value, [`key${char}`]: "v" });

    expect(result).not.toMatch(/[\x7f-\x9f]/);
    expect(result).toContain(escape);
    expect(parse(result)).toEqual({ arg: value, [`key${char}`]: "v" });
  });

  it("does not wrap long single-line values", () => {
    const value = "word ".repeat(40).trim();

    expect(toYaml({ arg: value })).toBe(`arg: ${value}`);
  });

  it("renders an empty object", () => {
    expect(toYaml({})).toBe("{}");
  });

  it("renders missing data as an empty string", () => {
    expect(toYaml(undefined as unknown as object)).toBe("");
  });

  it("keeps block style for strings with Windows line endings", () => {
    const result = toYaml({
      arg: "line1\r\nline2",
      nested: { list: ["a\r\nb"] },
    });

    expect(result).toBe(
      "arg: |-\n  line1\n  line2\nnested:\n  list:\n    - |-\n      a\n      b",
    );
  });

  it.each(["yes", "no", "on", "off", "y", "2001-12-14"])(
    "quotes %s so YAML 1.1 parsers keep it a string",
    (value) => {
      expect(toYaml({ arg: value })).toBe(`arg: "${value}"`);
    },
  );
});

describe("generateSyntaxHighlighterCode - pretty mode YAML fallback", () => {
  const prettifyConfig: PrettifyConfig = { fieldType: "input" };

  it("falls back to YAML when the data cannot be prettified", () => {
    const result = generate(
      { messages: [{ content: [] }] },
      MODE_TYPE.pretty,
      prettifyConfig,
    );

    expect(result.mode).toBe(MODE_TYPE.yaml);
    expect(result.prettified).toBe(false);
    expect(result.canBePrettified).toBe(false);
    expect(result.message).toBe("messages:\n  - content: []");
  });

  it("keeps block style for unicode in the fallback output", () => {
    const value = `role:${NBSP}assistant\ninstructions:\n  - cite sources`;

    const result = generate(
      { messages: [{ content: [] }], arg: value },
      MODE_TYPE.pretty,
      prettifyConfig,
    );

    expect(result.mode).toBe(MODE_TYPE.yaml);
    expect(result.message).toContain("|-");
    expect(result.message).not.toContain("\\n");
    expect(parse(result.message).arg).toBe(value);
  });
});
