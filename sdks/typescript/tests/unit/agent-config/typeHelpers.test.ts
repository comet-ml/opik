import {
  inferBackendType,
  serializeValue,
  deserializeValue,
} from "@/agent-config/typeHelpers";
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

describe("inferBackendType", () => {
  it('should return "string" for string values', () => {
    expect(inferBackendType("hello")).toBe("string");
    expect(inferBackendType("")).toBe("string");
  });

  it('should return "boolean" for boolean values', () => {
    expect(inferBackendType(true)).toBe("boolean");
    expect(inferBackendType(false)).toBe("boolean");
  });

  it('should return "integer" for integer numbers', () => {
    expect(inferBackendType(42)).toBe("integer");
    expect(inferBackendType(0)).toBe("integer");
    expect(inferBackendType(-10)).toBe("integer");
  });

  it('should return "float" for non-integer numbers', () => {
    expect(inferBackendType(3.14)).toBe("float");
    expect(inferBackendType(-0.5)).toBe("float");
  });

  it('should return "integer" for 1.0 (JS treats as integer)', () => {
    expect(inferBackendType(1.0)).toBe("integer");
  });

  it('should return "prompt" for BasePrompt instances', () => {
    expect(inferBackendType(makePromptLike("abc123de"))).toBe("prompt");
  });

  it('should return "prompt_commit" for PromptVersion instances', () => {
    expect(inferBackendType(makePromptVersion("abc123de"))).toBe(
      "prompt_commit"
    );
  });

  it('should return "string" for arrays', () => {
    expect(inferBackendType([1, 2, 3])).toBe("string");
    expect(inferBackendType([])).toBe("string");
  });

  it('should return "string" for plain objects', () => {
    expect(inferBackendType({ a: 1, b: "two" })).toBe("string");
    expect(inferBackendType({})).toBe("string");
  });
});

describe("serializeValue", () => {
  it("should serialize strings as-is", () => {
    expect(serializeValue("hello")).toBe("hello");
    expect(serializeValue("")).toBe("");
  });

  it('should serialize booleans as "true"/"false"', () => {
    expect(serializeValue(true)).toBe("true");
    expect(serializeValue(false)).toBe("false");
  });

  it("should serialize numbers to strings", () => {
    expect(serializeValue(42)).toBe("42");
    expect(serializeValue(3.14)).toBe("3.14");
    expect(serializeValue(0)).toBe("0");
    expect(serializeValue(-10)).toBe("-10");
  });

  it("should throw for NaN", () => {
    expect(() => serializeValue(NaN)).toThrow("non-finite");
  });

  it("should throw for Infinity", () => {
    expect(() => serializeValue(Infinity)).toThrow("non-finite");
    expect(() => serializeValue(-Infinity)).toThrow("non-finite");
  });

  it("should serialize BasePrompt using commit", () => {
    const prompt = makePromptLike("abc123de");
    expect(serializeValue(prompt)).toBe("abc123de");
  });

  it("should throw for BasePrompt without commit", () => {
    const prompt = makePromptLike(undefined as unknown as string);
    (prompt as { commit: unknown }).commit = undefined;
    expect(() => serializeValue(prompt)).toThrow("without a commit");
  });

  it("should serialize PromptVersion using commit", () => {
    const version = makePromptVersion("xyz789ab");
    expect(serializeValue(version)).toBe("xyz789ab");
  });

  it("should serialize arrays as JSON", () => {
    expect(serializeValue([1, 2, 3])).toBe("[1,2,3]");
    expect(serializeValue(["a", "b"])).toBe('["a","b"]');
  });

  it("should serialize plain objects as JSON", () => {
    expect(serializeValue({ a: 1 })).toBe('{"a":1}');
  });
});

describe("deserializeValue", () => {
  it("should deserialize booleans", () => {
    expect(deserializeValue("true", "boolean")).toBe(true);
    expect(deserializeValue("false", "boolean")).toBe(false);
  });

  it("should deserialize booleans case-insensitively", () => {
    expect(deserializeValue("TRUE", "boolean")).toBe(true);
    expect(deserializeValue("False", "boolean")).toBe(false);
  });

  it("should deserialize integers", () => {
    expect(deserializeValue("42", "integer")).toBe(42);
    expect(deserializeValue("0", "integer")).toBe(0);
    expect(deserializeValue("-10", "integer")).toBe(-10);
  });

  it("should truncate floats when deserializing as integer", () => {
    expect(deserializeValue("42.7", "integer")).toBe(42);
    expect(deserializeValue("42.3", "integer")).toBe(42);
  });

  it("should deserialize floats", () => {
    expect(deserializeValue("3.14", "float")).toBe(3.14);
    expect(deserializeValue("0.0", "float")).toBe(0);
    expect(deserializeValue("-0.5", "float")).toBe(-0.5);
  });

  it("should return strings as-is", () => {
    expect(deserializeValue("hello", "string")).toBe("hello");
    expect(deserializeValue("", "string")).toBe("");
  });

  it("should return prompt values as strings (commit hash)", () => {
    expect(deserializeValue("abc123de", "prompt")).toBe("abc123de");
  });

  it("should return prompt_commit values as strings (commit hash)", () => {
    expect(deserializeValue("abc123de", "prompt_commit")).toBe("abc123de");
  });
});

describe("round-trip: serialize then deserialize", () => {
  it("round-trips strings", () => {
    const val = "hello world";
    const backendType = inferBackendType(val);
    const serialized = serializeValue(val);
    expect(deserializeValue(serialized, backendType)).toBe(val);
  });

  it("round-trips integers", () => {
    const val = 42;
    const backendType = inferBackendType(val);
    const serialized = serializeValue(val);
    expect(deserializeValue(serialized, backendType)).toBe(val);
  });

  it("round-trips floats", () => {
    const val = 3.14;
    const backendType = inferBackendType(val);
    const serialized = serializeValue(val);
    expect(deserializeValue(serialized, backendType)).toBe(val);
  });

  it("round-trips booleans", () => {
    for (const val of [true, false]) {
      const backendType = inferBackendType(val);
      const serialized = serializeValue(val);
      expect(deserializeValue(serialized, backendType)).toBe(val);
    }
  });
});
