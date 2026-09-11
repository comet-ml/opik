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
