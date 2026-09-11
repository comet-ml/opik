import { IsJson } from "opik";

describe("IsJson Metric", () => {
  let isJson: IsJson;

  beforeEach(() => {
    isJson = new IsJson();
  });

  it("should return 1.0 for valid JSON objects", async () => {
    const result = await isJson.score({ output: '{ "key": "value" }' });
    expect(result.value).toBe(1.0);
    expect(result.reason).toContain("valid JSON");
  });

  it("should return 1.0 for valid JSON arrays", async () => {
    const result = await isJson.score({ output: '[1, 2, 3, "test"]' });
    expect(result.value).toBe(1.0);
  });

  it("should return 1.0 for primitive JSON values", async () => {
    const testCases = [
      '"simple string"',
      '123',
      'true',
      'null'
    ];

    for (const testCase of testCases) {
      const result = await isJson.score({ output: testCase });
      expect(result.value).toBe(1.0);
    }
  });

  it("should return 0.0 for invalid JSON strings", async () => {
    const testCases = [
      '{ key: "value" }',  // missing quotes around key
      '{ "key": value }',  // unquoted value
      '[1, 2, 3, ]',       // trailing comma
      '{ "nested": { "key": "value" }',  // missing closing brace
      'not json at all',
      '',
      '   ',
      'undefined',
      'NaN'
    ];

    for (const testCase of testCases) {
      const result = await isJson.score({ output: testCase });
      expect(result.value).toBe(0.0);
      expect(result.reason).toContain("not valid JSON");
    }
  });

  it("should handle complex nested JSON structures", async () => {
    const complexJson = JSON.stringify({
      array: [1, 2, { key: "value" }],
      nested: {
        boolean: true,
        number: 42,
        string: "test",
        nullValue: null
      }
    });

    const result = await isJson.score({ output: complexJson });
    expect(result.value).toBe(1.0);
  });

  it("should respect custom metric name", async () => {
    const customName = "custom_json_check";
    const customIsJson = new IsJson(customName);
    const result = await customIsJson.score({ output: '{}' });
    expect(result.name).toBe(customName);
  });

  it("should handle whitespace correctly", async () => {
    const validWithWhitespace = '\n\t{ \n\t\t"key"\t: \t"value"\n\t}\n';
    const result = await isJson.score({ output: validWithWhitespace });
    expect(result.value).toBe(1.0);
  });
});