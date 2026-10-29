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
  ])("escapes %s in values and keys", (_label, char, escape) => {
    const value = `line1${char}x\nline2`;

    const result = toYaml({ arg: value, [`key${char}`]: "v" });

    expect(result).not.toMatch(/[\x7f-\x9f]/);
    expect(result).toContain(escape);
    expect(parse(result)).toEqual({ arg: value, [`key${char}`]: "v" });
  });

  it.each(["=", "<<"])(
    "quotes %s in values, keys and lists so YAML 1.1 parsers load it",
    (value) => {
      const data = { arg: value, [value]: "v", list: [value] };

      const result = toYaml(data);

      expect(result).toBe(
        `arg: '${value}'\n'${value}': v\nlist:\n  - '${value}'`,
      );
      expect(parse(result, { version: "1.1" })).toEqual(data);
    },
  );

  it.each(["0o17", "0o0", "-0o17", "+0o0"])(
    "quotes %s so YAML 1.2 parsers keep it a string",
    (value) => {
      const data = { arg: value, [value]: "v", list: [value] };

      const result = toYaml(data);

      expect(result).toBe(
        `arg: '${value}'\n'${value}': v\nlist:\n  - '${value}'`,
      );
      expect(parse(result)).toEqual(data);
    },
  );

  it("leaves numbers and 0o-like text unquoted", () => {
    expect(toYaml({ count: 15, a: "0o8", b: "0o17x" })).toBe(
      "count: 15\na: 0o8\nb: 0o17x",
    );
  });

  it("keeps = and << unquoted inside longer text", () => {
    expect(toYaml({ a: "a = b", b: "<<x" })).toBe("a: a = b\nb: <<x");
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

  it.each([
    ["an NBSP-only value", { k: NBSP }],
    ["a trailing NBSP", { k: `text${NBSP}` }],
    ["trailing spaces on the last line", { k: "line1\ntrailing  \n" }],
  ])("keeps %s", (_label, data) => {
    expect(parse(toYaml(data))).toEqual(data);
  });

  it.each([
    ["ends in one newline", { k: "a\nb\n" }, "k: |\n  a\n  b\n"],
    ["ends in several newlines", { k: "a\n\n" }, "k: |+\n  a\n\n"],
    [
      "is followed by another key",
      { k: "a\nb\n", z: 1 },
      "k: |\n  a\n  b\nz: 1",
    ],
  ])(
    "preserves required trailing newlines when the string %s",
    (_label, data, expected) => {
      const result = toYaml(data);

      expect(result).toBe(expected);
      expect(parse(result)).toEqual(data);
    },
  );

  it("indents a top-level multiline string", () => {
    const value = "You are a helpful assistant.\nAnswer briefly.";

    const result = toYaml(value as unknown as object);

    expect(result).toBe(
      "|-\n  You are a helpful assistant.\n  Answer briefly.",
    );
    expect(parse(result)).toBe(value);
  });

  it("quotes a top-level string whose first line is indented", () => {
    const value = "  indented first\nsecond";

    const result = toYaml(value as unknown as object);

    expect(result).toBe('"  indented first\\nsecond"');
    expect(parse(result)).toBe(value);
  });
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
