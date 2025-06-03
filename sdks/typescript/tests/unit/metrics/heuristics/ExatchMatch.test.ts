import { ExactMatch } from "opik";

describe("ExactMatch Metric", () => {
  let exactMatch: ExactMatch;

  beforeEach(() => {
    exactMatch = new ExactMatch();
  });

  it("should return 1.0 for exact string matches", async () => {
    const result = await exactMatch.score("hello", "hello");
    expect(result.value).toBe(1.0);
    expect(result.reason).toContain("Exact match: Match");
  });

  it("should return 0.0 for non-matching strings", async () => {
    const result = await exactMatch.score("hello", "world");
    expect(result.value).toBe(0.0);
    expect(result.reason).toContain("Exact match: No match");
  });

  it("should be case-sensitive", async () => {
    const result = await exactMatch.score("Hello", "hello");
    expect(result.value).toBe(0.0);
  });

  it("should handle empty strings correctly", async () => {
    const emptyResult = await exactMatch.score("", "");
    expect(emptyResult.value).toBe(1.0);

    const nonEmptyResult = await exactMatch.score("", "test");
    expect(nonEmptyResult.value).toBe(0.0);
  });

  it("should respect custom metric name", async () => {
    const customName = "custom_exact_match";
    const customExactMatch = new ExactMatch(customName);
    const result = await customExactMatch.score("test", "test");
    expect(result.name).toBe(customName);
  });

  it("should handle whitespace differences", async () => {
    const result1 = await exactMatch.score("hello", "hello ");
    const result2 = await exactMatch.score("\thello\n", "hello");

    expect(result1.value).toBe(0.0);
    expect(result2.value).toBe(0.0);
  });

  it("should handle special characters", async () => {
    const specialString = "!@#$%^&*()_+{}|:<>?~`-='\"\\";
    const result = await exactMatch.score(specialString, specialString);
    expect(result.value).toBe(1.0);
  });
});
