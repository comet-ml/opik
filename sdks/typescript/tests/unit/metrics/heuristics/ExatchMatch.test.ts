import { ExactMatch } from "opik";

describe("ExactMatch Metric", () => {
  let exactMatch: ExactMatch;

  beforeEach(() => {
    exactMatch = new ExactMatch();
  });

  it("should return 1.0 for exact string matches", async () => {
    const result = await exactMatch.score({
      output: "hello",
      expected: "hello",
    });
    expect(result.value).toBe(1.0);
    expect(result.reason).toContain("Exact match: Match");
  });

  it("should return 0.0 for non-matching strings", async () => {
    const result = await exactMatch.score({
      output: "hello",
      expected: "world",
    });
    expect(result.value).toBe(0.0);
    expect(result.reason).toContain("Exact match: No match");
  });

  it("should be case-sensitive by default", async () => {
    const result = await exactMatch.score({
      output: "Hello",
      expected: "hello",
    });
    expect(result.value).toBe(0.0);
  });

  it("should allow case-insensitive string matching", async () => {
    const caseInsensitive = new ExactMatch("case_insensitive", true, false);
    const result = await caseInsensitive.score({
      output: "Hello",
      expected: "hello",
    });
    expect(result.value).toBe(1.0);
  });

  it("should compare objects by content regardless of key order", async () => {
    const result = await exactMatch.score({
      output: { first: "hello", nested: [1, { value: true }] },
      expected: { nested: [1, { value: true }], first: "hello" },
    });
    expect(result.value).toBe(1.0);
  });

  it("should preserve array order when comparing content", async () => {
    const matching = await exactMatch.score({
      output: [1, { value: "hello" }],
      expected: [1, { value: "hello" }],
    });
    const differentOrder = await exactMatch.score({
      output: [1, 2],
      expected: [2, 1],
    });

    expect(matching.value).toBe(1.0);
    expect(differentOrder.value).toBe(0.0);
  });

  it("should not coerce scalar types", async () => {
    const result = await exactMatch.score({ output: 1, expected: "1" });
    expect(result.value).toBe(0.0);
  });

  it("should retain strict equality for non-JSON values", async () => {
    const nan = await exactMatch.score({ output: NaN, expected: NaN });
    const map = await exactMatch.score({
      output: new Map([["value", 1]]),
      expected: new Map([["value", 1]]),
    });
    const nestedMap = await exactMatch.score({
      output: { value: new Map([["value", 1]]) },
      expected: { value: new Map([["value", 2]]) },
    });
    const sparseOutput = Object.assign(new Array(1), { tag: "output" });
    const sparseExpected = Object.assign(new Array(1), { tag: "expected" });
    const sparseArray = await exactMatch.score({
      output: sparseOutput,
      expected: sparseExpected,
    });
    const jsonString = await exactMatch.score({
      output: '{"value":1}',
      expected: { value: 1 },
    });

    expect(nan.value).toBe(0.0);
    expect(map.value).toBe(0.0);
    expect(nestedMap.value).toBe(0.0);
    expect(sparseArray.value).toBe(0.0);
    expect(jsonString.value).toBe(0.0);
  });

  it("should handle empty strings correctly", async () => {
    const emptyResult = await exactMatch.score({ output: "", expected: "" });
    expect(emptyResult.value).toBe(1.0);

    const nonEmptyResult = await exactMatch.score({
      output: "",
      expected: "test",
    });
    expect(nonEmptyResult.value).toBe(0.0);
  });

  it("should respect custom metric name", async () => {
    const customName = "custom_exact_match";
    const customExactMatch = new ExactMatch(customName);
    const result = await customExactMatch.score({
      output: "test",
      expected: "test",
    });
    expect(result.name).toBe(customName);
  });

  it("should handle whitespace differences", async () => {
    const result1 = await exactMatch.score({
      output: "hello",
      expected: "hello ",
    });
    const result2 = await exactMatch.score({
      output: "\thello\n",
      expected: "hello",
    });

    expect(result1.value).toBe(0.0);
    expect(result2.value).toBe(0.0);
  });

  it("should handle special characters", async () => {
    const specialString = "!@#$%^&*()_+{}|:<>?~`-='\"\\";
    const result = await exactMatch.score({
      output: specialString,
      expected: specialString,
    });
    expect(result.value).toBe(1.0);
  });
});
