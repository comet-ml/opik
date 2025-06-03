import { Contains } from "opik";

describe("Contains Metric", () => {
  describe("case-insensitive (default)", () => {
    const contains = new Contains();

    it("should return 1.0 when output contains the expected string (case-insensitive match)", async () => {
      const result = await contains.score("Hello World", "hello");
      expect(result.value).toBe(1.0);
      expect(result.reason).toContain("found in output");
    });

    it("should return 0.0 when output does not contain the expected string", async () => {
      const result = await contains.score("Hello World", "goodbye");
      expect(result.value).toBe(0.0);
      expect(result.reason).toContain("not found in output");
    });

    it("should handle empty strings correctly", async () => {
      const result = await contains.score("", "test");
      expect(result.value).toBe(0.0);

      const result2 = await contains.score("test", "");
      expect(result2.value).toBe(1.0);
    });
  });

  describe("case-sensitive", () => {
    const caseSensitiveContains = new Contains(
      "case_sensitive_contains",
      true,
      undefined,
      true,
    );

    it("should be case-sensitive when configured", async () => {
      const result1 = await caseSensitiveContains.score("Hello World", "hello");
      expect(result1.value).toBe(0.0);

      const result2 = await caseSensitiveContains.score("Hello World", "Hello");
      expect(result2.value).toBe(1.0);
    });
  });

  it("should use custom metric name when provided", async () => {
    const customName = "custom_contains";
    const customContains = new Contains(customName);
    const result = await customContains.score("test", "test");
    expect(result.name).toBe(customName);
  });

  it("should handle exact matches", async () => {
    const contains = new Contains();
    const result = await contains.score("exact", "exact");
    expect(result.value).toBe(1.0);
  });

  it("should handle partial matches at start, middle, and end", async () => {
    const contains = new Contains();
    const testString = "this is a test string";

    const startMatch = await contains.score(testString, "this is");
    const middleMatch = await contains.score(testString, "a test");
    const endMatch = await contains.score(testString, "string");

    expect(startMatch.value).toBe(1.0);
    expect(middleMatch.value).toBe(1.0);
    expect(endMatch.value).toBe(1.0);
  });
});
