import { describe, it, expect } from "vitest";
import {
  detectConfigValueType,
  flattenConfig,
  shouldSkipRedundantKey,
  makeSkipKey,
  EXCLUDED_CONFIG_KEYS,
} from "./configuration-renderer";

describe("detectConfigValueType", () => {
  it("detects prompt keys with string values", () => {
    expect(detectConfigValueType("system_prompt", "Hello")).toBe("prompt");
    expect(detectConfigValueType("user_prompt", "World")).toBe("prompt");
    expect(detectConfigValueType("template", "tmpl")).toBe("prompt");
  });

  it("detects prompt keys with messages array", () => {
    const messages = [
      { role: "system", content: "You are helpful" },
      { role: "user", content: "Hi" },
    ];
    expect(detectConfigValueType("prompt", messages)).toBe("prompt");
    expect(detectConfigValueType("messages", messages)).toBe("prompt");
  });

  it("detects prompt keys with messages wrapper object", () => {
    const value = {
      messages: [{ role: "system", content: "Hello" }],
    };
    expect(detectConfigValueType("prompt", value)).toBe("prompt");
  });

  it("detects prompt keys with NamedPrompts format", () => {
    const value = {
      "chat-prompt": [
        { role: "system", content: "You are helpful" },
        { role: "user", content: "{question}" },
      ],
    };
    expect(detectConfigValueType("prompt", value)).toBe("prompt");

    const multiPrompt = {
      "agent-1": [{ role: "system", content: "Agent 1" }],
      "agent-2": [{ role: "user", content: "Agent 2" }],
    };
    expect(detectConfigValueType("prompt", multiPrompt)).toBe("prompt");
  });

  it("does not detect as prompt when object values are not message arrays", () => {
    const value = {
      "chat-prompt": "just a string",
    };
    expect(detectConfigValueType("prompt", value)).not.toBe("prompt");
  });

  it("detects tools keys with array values", () => {
    expect(detectConfigValueType("tools", [{ name: "search" }])).toBe("tools");
    expect(detectConfigValueType("functions", [{ name: "fn" }])).toBe("tools");
  });

  it("detects primitive types", () => {
    expect(detectConfigValueType("temperature", 0.7)).toBe("number");
    expect(detectConfigValueType("model", "gpt-4")).toBe("string");
    expect(detectConfigValueType("stream", true)).toBe("boolean");
    expect(detectConfigValueType("verbose", false)).toBe("boolean");
  });

  it("detects json_object for objects and arrays", () => {
    expect(detectConfigValueType("config", { a: 1 })).toBe("json_object");
    expect(detectConfigValueType("items", [1, 2, 3])).toBe("json_object");
  });

  it("returns unknown for null/undefined", () => {
    expect(detectConfigValueType("field", null)).toBe("unknown");
    expect(detectConfigValueType("field", undefined)).toBe("unknown");
  });
});

describe("shouldSkipRedundantKey", () => {
  it("skips redundant keys when structured prompt exists", () => {
    expect(shouldSkipRedundantKey("system_prompt", true)).toBe(true);
    expect(shouldSkipRedundantKey("user_prompt", true)).toBe(true);
    expect(shouldSkipRedundantKey("user_message", true)).toBe(true);
  });

  it("does not skip when no structured prompt", () => {
    expect(shouldSkipRedundantKey("system_prompt", false)).toBe(false);
    expect(shouldSkipRedundantKey("user_prompt", false)).toBe(false);
  });

  it("does not skip non-redundant keys", () => {
    expect(shouldSkipRedundantKey("temperature", true)).toBe(false);
    expect(shouldSkipRedundantKey("model", true)).toBe(false);
  });
});

describe("EXCLUDED_CONFIG_KEYS", () => {
  it("excludes prompt and examples", () => {
    expect(EXCLUDED_CONFIG_KEYS).toContain("prompt");
    expect(EXCLUDED_CONFIG_KEYS).toContain("examples");
  });
});

describe("flattenConfig", () => {
  it("flattens simple primitive config without skipKey", () => {
    const config = {
      temperature: 0.7,
      model: "gpt-4",
      max_tokens: 100,
    };

    const result = flattenConfig(config);

    expect(result).toEqual([
      { key: "temperature", value: 0.7, type: "number" },
      { key: "model", value: "gpt-4", type: "string" },
      { key: "max_tokens", value: 100, type: "number" },
    ]);
  });

  it("includes all keys when no skipKey is provided", () => {
    const config = {
      temperature: 0.7,
      prompt: [{ role: "system", content: "Hello" }],
      examples: [{ input: "a", output: "b" }],
    };

    const result = flattenConfig(config);

    expect(result).toHaveLength(3);
    expect(result.map((e) => e.key)).toEqual([
      "temperature",
      "prompt",
      "examples",
    ]);
  });

  it("excludes keys matched by skipKey callback", () => {
    const config = {
      temperature: 0.7,
      prompt: [{ role: "system", content: "Hello" }],
      examples: [{ input: "a", output: "b" }],
    };

    const result = flattenConfig(config, makeSkipKey(false));

    expect(result).toEqual([
      { key: "temperature", value: 0.7, type: "number" },
    ]);
  });

  it("recursively flattens nested json_object values", () => {
    const config = {
      llm_model: {
        model: "gpt-4",
        temperature: 0.5,
      },
      top_k: 10,
    };

    const result = flattenConfig(config);

    expect(result).toEqual([
      { key: "llm_model.model", value: "gpt-4", type: "string" },
      { key: "llm_model.temperature", value: 0.5, type: "number" },
      { key: "top_k", value: 10, type: "number" },
    ]);
  });

  it("skips redundant keys when hasStructuredPrompt is true", () => {
    const config = {
      system_prompt: "You are helpful",
      user_prompt: "Hello",
      user_message: "Hi",
      temperature: 0.7,
    };

    const result = flattenConfig(config, makeSkipKey(true));

    expect(result).toEqual([
      { key: "temperature", value: 0.7, type: "number" },
    ]);
  });

  it("keeps redundant keys when hasStructuredPrompt is false", () => {
    const config = {
      system_prompt: "You are helpful",
      temperature: 0.7,
    };

    const result = flattenConfig(config, makeSkipKey(false));

    expect(result).toEqual([
      { key: "system_prompt", value: "You are helpful", type: "prompt" },
      { key: "temperature", value: 0.7, type: "number" },
    ]);
  });

  it("includes prompt and tools entries from nested paths", () => {
    const config = {
      nested: {
        prompt_messages: [{ role: "user", content: "Hi" }],
        temperature: 0.5,
      },
    };

    const result = flattenConfig(config);

    expect(result).toEqual([
      {
        key: "nested.prompt_messages",
        value: [{ role: "user", content: "Hi" }],
        type: "prompt",
      },
      { key: "nested.temperature", value: 0.5, type: "number" },
    ]);
  });

  it("only applies skipKey to root-level keys", () => {
    const config = {
      nested: {
        prompt: "nested prompt value",
      },
    };

    const result = flattenConfig(config, makeSkipKey(false));

    expect(result).toEqual([
      { key: "nested.prompt", value: "nested prompt value", type: "prompt" },
    ]);
  });

  it("handles empty config", () => {
    expect(flattenConfig({})).toEqual([]);
    expect(flattenConfig({}, makeSkipKey(true))).toEqual([]);
  });

  it("handles deeply nested objects", () => {
    const config = {
      level1: {
        level2: {
          value: 42,
        },
      },
    };

    const result = flattenConfig(config);

    expect(result).toEqual([
      { key: "level1.level2.value", value: 42, type: "number" },
    ]);
  });

  it("does not recurse into arrays", () => {
    const config = {
      items: [1, 2, 3],
    };

    const result = flattenConfig(config);

    expect(result).toEqual([
      { key: "items", value: [1, 2, 3], type: "json_object" },
    ]);
  });
});
