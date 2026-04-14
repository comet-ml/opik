import {
  serializeValue,
  deserializeValue,
} from "@/typeHelpers";
import { BasePrompt } from "@/prompt/BasePrompt";
import { PromptVersion } from "@/prompt/PromptVersion";

function makePromptLike(commit: string): BasePrompt {
  const obj = Object.create(BasePrompt.prototype);
  obj.commit = commit;
  obj.versionId = "version-id-123";
  return obj;
}

function makePromptVersion(commit: string): PromptVersion {
  return new PromptVersion({
    name: "test-prompt",
    prompt: "Hello {{name}}",
    commit,
    promptId: "prompt-id-1",
    versionId: "version-id-1",
    type: "mustache",
  });
}

describe("serializeValue", () => {
  it.each([
    ["hello", "hello"],
    ["", ""],
  ])("serializes string %j as-is", (input, expected) => {
    expect(serializeValue(input)).toBe(expected);
  });

  it.each([
    [true, "true"],
    [false, "false"],
  ])("serializes boolean %s as %j", (input, expected) => {
    expect(serializeValue(input)).toBe(expected);
  });

  it.each([
    [42, "42"],
    [3.14, "3.14"],
    [0, "0"],
    [-10, "-10"],
  ])("serializes number %s to string", (input, expected) => {
    expect(serializeValue(input)).toBe(expected);
  });

  it.each([NaN, Infinity, -Infinity])(
    "throws for non-finite number %s",
    (input) => {
      expect(() => serializeValue(input)).toThrow("non-finite");
    }
  );

  it("serializes BasePrompt using commit", () => {
    expect(serializeValue(makePromptLike("abc123de"))).toBe("abc123de");
  });

  it("throws for BasePrompt without commit", () => {
    const prompt = makePromptLike(undefined as unknown as string);
    (prompt as { commit: unknown }).commit = undefined;
    expect(() => serializeValue(prompt)).toThrow("without a commit");
  });

  it("serializes PromptVersion using commit", () => {
    expect(serializeValue(makePromptVersion("xyz789ab"))).toBe("xyz789ab");
  });

  it.each([
    [[1, 2, 3], "[1,2,3]"],
    [["a", "b"], '["a","b"]'],
  ])("serializes array %j as JSON", (input, expected) => {
    expect(serializeValue(input)).toBe(expected);
  });

  it("serializes plain objects as JSON", () => {
    expect(serializeValue({ a: 1 })).toBe('{"a":1}');
  });
});

describe("deserializeValue", () => {
  it.each([
    ["true", true],
    ["false", false],
    ["TRUE", true],
    ["False", false],
  ])("deserializes boolean string %j to %s", (input, expected) => {
    expect(deserializeValue(input, "boolean")).toBe(expected);
  });

  it.each([
    ["42", 42],
    ["0", 0],
    ["-10", -10],
    ["42.7", 42],
    ["42.3", 42],
  ])("deserializes integer string %j to %s", (input, expected) => {
    expect(deserializeValue(input, "integer")).toBe(expected);
  });

  it.each([
    ["3.14", 3.14],
    ["0.0", 0],
    ["-0.5", -0.5],
  ])("deserializes float string %j to %s", (input, expected) => {
    expect(deserializeValue(input, "float")).toBe(expected);
  });

  it.each([
    ["hello", "hello"],
    ["", ""],
  ])("returns string %j as-is", (input, expected) => {
    expect(deserializeValue(input, "string")).toBe(expected);
  });

  it.each(["prompt", "prompt_commit"] as const)(
    "returns %s values as strings (commit hash)",
    (type) => {
      expect(deserializeValue("abc123de", type)).toBe("abc123de");
    }
  );
});

describe("serializeValue — explicit backendType for prompt types", () => {
  it('serializes BasePrompt with backendType "prompt" → returns commit', () => {
    const prompt = makePromptLike("commit-abc123");
    expect(serializeValue(prompt, "prompt")).toBe("commit-abc123");
  });

  it('throws when serializing BasePrompt without commit and backendType "prompt"', () => {
    const prompt = makePromptLike(undefined as unknown as string);
    (prompt as { commit: unknown }).commit = undefined;
    expect(() => serializeValue(prompt, "prompt")).toThrow("without a commit");
  });

  it('serializes PromptVersion with backendType "prompt_commit" → returns commit', () => {
    const pv = makePromptVersion("commit-xyz789");
    expect(serializeValue(pv, "prompt_commit")).toBe("commit-xyz789");
  });
});

describe("round-trip: serialize then deserialize", () => {
  it.each([
    ["hello world", "string"],
    [42, "integer"],
    [3.14, "float"],
    [true, "boolean"],
    [false, "boolean"],
  ] as const)("round-trips %j as %s", (val, type) => {
    expect(deserializeValue(serializeValue(val, type), type)).toBe(val);
  });
});
