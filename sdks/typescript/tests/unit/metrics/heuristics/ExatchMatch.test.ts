import { ExactMatch } from "opik";

describe("ExactMatch Metric", () => {
  let exactMatch: ExactMatch;

  beforeEach(() => {
    exactMatch = new ExactMatch();
  });

  it("should return 1.0 for exact string matches", async () => {
    const result = await exactMatch.score({ output: "hello", expected: "hello" });
    expect(result.value).toBe(1.0);
    expect(result.reason).toContain("Exact match: Match");
  });

  it("should return 0.0 for non-matching strings", async () => {
    const result = await exactMatch.score({ output: "hello", expected: "world" });
    expect(result.value).toBe(0.0);
    expect(result.reason).toContain("Exact match: No match");
  });

  it("should be case-sensitive", async () => {
    const result = await exactMatch.score({ output: "Hello", expected: "hello" });
    expect(result.value).toBe(0.0);
  });

  it("should compare strings case-insensitively when configured", async () => {
    const ignoreCase = new ExactMatch("exact_match", false, false);
    const result = await ignoreCase.score({
      output: "Hello",
      expected: "hello",
    });
    expect(result.value).toBe(1.0);
  });

  it("should match object values regardless of key order", async () => {
    const result = await exactMatch.score({
      output: { answer: "Paris", citations: ["1", "2"] },
      expected: { citations: ["1", "2"], answer: "Paris" },
    });
    expect(result.value).toBe(1.0);
  });

  describe("own enumerable symbol properties", () => {
    const key = Symbol("answer");
    const otherKey = Symbol("answer");
    const hidden = Object.defineProperty({}, key, { value: 1 });

    it.each([
      [
        "nested matching values",
        { [key]: { value: 1 } },
        { [key]: { value: 1 } },
        1,
      ],
      ["different values", { [key]: 1 }, { [key]: 2 }, 0],
      ["a missing key", { [key]: 1 }, {}, 0],
      ["different key identities", { [key]: 1 }, { [otherKey]: 1 }, 0],
      ["a non-enumerable key", hidden, {}, 1],
      [
        "different enumerable keys",
        { [key]: 1 },
        Object.defineProperty({ [otherKey]: 1 }, key, { value: 1 }),
        0,
      ],
      ["different array properties", Object.assign([1], { [key]: 1 }), [1], 0],
    ] as const)("should compare %s", async (_label, output, expected, value) => {
      expect((await exactMatch.score({ output, expected })).value).toBe(value);
    });
  });

  it("should apply the configured string comparison inside arrays and objects", async () => {
    const ignoreCase = new ExactMatch("exact_match", false, false);
    const input = {
      output: [{ answer: "Paris" }],
      expected: [{ answer: "PARIS" }],
    };
    expect((await exactMatch.score(input)).value).toBe(0.0);
    expect((await ignoreCase.score(input)).value).toBe(1.0);
  });

  it.each([
    { output: ["a", "b"], expected: ["b", "a"] },
    { output: ["a"], expected: { 0: "a" } },
    { output: { a: 1 }, expected: '{"a":1}' },
    { output: 1, expected: "1" },
    { output: true, expected: 1 },
  ])(
    "should distinguish values with different order or types (%#)",
    async (input) => {
      expect((await exactMatch.score(input)).value).toBe(0.0);
    },
  );

  it("should preserve reference comparison for cyclic and non-plain objects", async () => {
    const output: { self?: unknown } = {};
    output.self = output;
    const expected: { self?: unknown } = {};
    expected.self = expected;
    expect((await exactMatch.score({ output, expected })).value).toBe(0.0);
    expect((await exactMatch.score({ output, expected: output })).value).toBe(
      1.0,
    );
    expect(
      (await exactMatch.score({ output: new Date(0), expected: new Date(0) }))
        .value,
    ).toBe(0.0);
  });

  it("should compare deeply nested JSON without exhausting the stack", async () => {
    const json = "[".repeat(4000) + '"value"' + "]".repeat(4000);
    const result = await exactMatch.score({
      output: JSON.parse(json),
      expected: JSON.parse(json),
    });
    expect(result.value).toBe(1.0);
  });

  it("should compare a shared subobject each time it appears", async () => {
    const shared = { answer: "Paris" };
    const result = await exactMatch.score({
      output: [shared, shared],
      expected: [{ answer: "Paris" }, { answer: "Paris" }],
    });
    expect(result.value).toBe(1.0);
  });

  it("should handle empty strings correctly", async () => {
    const emptyResult = await exactMatch.score({ output: "", expected: "" });
    expect(emptyResult.value).toBe(1.0);

    const nonEmptyResult = await exactMatch.score({ output: "", expected: "test" });
    expect(nonEmptyResult.value).toBe(0.0);
  });

  it("should respect custom metric name", async () => {
    const customName = "custom_exact_match";
    const customExactMatch = new ExactMatch(customName);
    const result = await customExactMatch.score({ output: "test", expected: "test" });
    expect(result.name).toBe(customName);
  });

  it("should handle whitespace differences", async () => {
    const result1 = await exactMatch.score({ output: "hello", expected: "hello " });
    const result2 = await exactMatch.score({ output: "\thello\n", expected: "hello" });

    expect(result1.value).toBe(0.0);
    expect(result2.value).toBe(0.0);
  });

  it("should handle special characters", async () => {
    const specialString = "!@#$%^&*()_+{}|:<>?~`-='\"\\";
    const result = await exactMatch.score({ 
      output: specialString, 
      expected: specialString 
    });
    expect(result.value).toBe(1.0);
  });
});
