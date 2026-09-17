import { describe, it, expect } from "vitest";
import { parse } from "yaml";
import { generateSyntaxHighlighterCode } from "./utils";
import { MODE_TYPE } from "./constants";

const NBSP = "\u00A0";
const LINE_SEPARATOR = "\u2028";
const DEL = "\u007F";

const toYaml = (data: object) =>
  generateSyntaxHighlighterCode(data, MODE_TYPE.yaml).message;

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

  it("does not wrap long single-line values", () => {
    const value = "word ".repeat(40).trim();

    expect(toYaml({ arg: value })).toBe(`arg: ${value}`);
  });

  it("renders an empty object", () => {
    expect(toYaml({})).toBe("{}");
  });
});
