import { RegexMatch } from "opik";

describe("RegexMatch Metric", () => {
  let regexMatch: RegexMatch;

  beforeEach(() => {
    regexMatch = new RegexMatch();
  });

  it("should return 1.0 for matching patterns", async () => {
    const result = await regexMatch.score({ output: "hello world", pattern: "hello" });
    expect(result.value).toBe(1.0);
    expect(result.reason).toContain("matches the regex pattern");
  });

  it("should return 0.0 for non-matching patterns", async () => {
    const result = await regexMatch.score({ output: "hello world", pattern: "goodbye" });
    expect(result.value).toBe(0.0);
    expect(result.reason).toContain("does not match the regex pattern");
  });

  it("should handle complex regex patterns", async () => {
    const emailPattern = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";

    const validEmail = await regexMatch.score({ 
      output: "test@example.com", 
      pattern: emailPattern 
    });
    const invalidEmail = await regexMatch.score({ 
      output: "not-an-email", 
      pattern: emailPattern 
    });

    expect(validEmail.value).toBe(1.0);
    expect(invalidEmail.value).toBe(0.0);
  });

  it("should handle regex flags correctly", async () => {
    const caseSensitive = await regexMatch.score({ 
      output: "Hello", 
      pattern: "hello" 
    });
    const caseInsensitive = await regexMatch.score({ 
      output: "Hello", 
      pattern: "hello",
      flags: "i" 
    });

    expect(caseSensitive.value).toBe(0.0);
    expect(caseInsensitive.value).toBe(1.0);
  });

  it("should handle empty strings and patterns", async () => {
    const emptyPattern = await regexMatch.score({ 
      output: "any string", 
      pattern: "" 
    });
    const emptyString = await regexMatch.score({ 
      output: "", 
      pattern: ".*" 
    });
    const bothEmpty = await regexMatch.score({ 
      output: "", 
      pattern: "" 
    });

    expect(emptyPattern.value).toBe(1.0); // empty pattern matches anything
    expect(emptyString.value).toBe(1.0); // .* matches empty string
    expect(bothEmpty.value).toBe(1.0); // both empty should match
  });

  it("should respect custom metric name", async () => {
    const customName = "custom_regex_match";
    const customRegexMatch = new RegexMatch(customName);
    const result = await customRegexMatch.score({ 
      output: "test", 
      pattern: ".*" 
    });
    expect(result.name).toBe(customName);
  });

  it("should handle special regex characters", async () => {
    const specialPattern = "\\d{3}-\\d{2}-\\d{4}"; // SSN-like pattern
    const result1 = await regexMatch.score({ 
      output: "123-45-6789", 
      pattern: specialPattern 
    });
    const result2 = await regexMatch.score({ 
      output: "abc-12-3456", 
      pattern: specialPattern 
    });

    expect(result1.value).toBe(1.0);
    expect(result2.value).toBe(0.0);
  });

  it("should handle invalid regex patterns", async () => {
    await expect(regexMatch.score({ 
      output: "test", 
      pattern: "(unclosed" 
    })).rejects.toThrow("Invalid regular expression");
  });
});
