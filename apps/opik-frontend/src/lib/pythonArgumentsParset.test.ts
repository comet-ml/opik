import { describe, it, expect } from "vitest";
import {
  parsePythonMethodParameters,
  splitWithBracketPreservation,
  createMethodRegex,
} from "./pythonArgumentsParser";

describe("createMethodRegex", () => {
  // Basic method signature tests
  it("matches simple method without parameters", () => {
    const regex = createMethodRegex("simple_method");
    const testCases = ["def simple_method():", "def simple_method():"];

    testCases.forEach((testCase) => {
      const match = testCase.match(regex);
      expect(match).not.toBeNull();
      expect(match?.[1]).toBe(""); // No parameters
    });
  });

  // Method with parameters tests
  it("matches method with basic parameters", () => {
    const regex = createMethodRegex("method_with_params");
    const testCases = [
      "def method_with_params(a, b, c):",
      "def method_with_params(x, y):",
    ];

    testCases.forEach((testCase) => {
      const match = testCase.match(regex);
      expect(match).not.toBeNull();
      expect(match?.[1]).toBeTruthy(); // Has parameters
    });
  });

  // Typed parameter tests
  it("matches methods with type annotations", () => {
    const regex = createMethodRegex("typed_method");
    const testCases = [
      "def typed_method(a: int, b: str):",
      "def typed_method(x: list, y: dict) -> int:",
      "def typed_method(a: int) -> str:",
    ];

    testCases.forEach((testCase) => {
      const match = testCase.match(regex);
      expect(match).not.toBeNull();
    });
  });

  // Multiline method signature tests
  it("matches multiline method signatures", () => {
    const regex = createMethodRegex("multiline_method");
    const testCases = [
      `def multiline_method(
        a: int,
        b: str
      ):`,
      `def multiline_method[T](
        x: T,
        y: list
      ) -> T:`,
    ];

    testCases.forEach((testCase) => {
      const match = testCase.match(regex);
      expect(match).not.toBeNull();
    });
  });

  // Negative tests
  it("does not match incorrect method signatures", () => {
    const regex = createMethodRegex("invalid_method");
    const testCases = [
      "function invalid_method():",
      "def other_method():",
      "invalid_method(a, b)",
      "class invalid_method:",
    ];

    testCases.forEach((testCase) => {
      const match = testCase.match(regex);
      expect(match).toBeNull();
    });
  });
});

describe("splitWithBracketPreservation", () => {
  it("should split simple comma-separated string", () => {
    const input = "input: str, output: int";
    const result = splitWithBracketPreservation(input);
    expect(result).toEqual(["input: str", "output: int"]);
  });

  it("should preserve type annotations with brackets", () => {
    const input = "input: List[str], output: Dict[str, int]";
    const result = splitWithBracketPreservation(input);
    expect(result).toEqual(["input: List[str]", "output: Dict[str, int]"]);
  });

  it("should handle nested bracket types", () => {
    const input = "data: List[Tuple[str, int]], other: Dict[str, List[float]]";
    const result = splitWithBracketPreservation(input);
    expect(result).toEqual([
      "data: List[Tuple[str, int]]",
      "other: Dict[str, List[float]]",
    ]);
  });

  it("should handle multiple nested bracket levels", () => {
    const input = "complex: Dict[str, List[Tuple[int, float]]], simple: str";
    const result = splitWithBracketPreservation(input);
    expect(result).toEqual([
      "complex: Dict[str, List[Tuple[int, float]]]",
      "simple: str",
    ]);
  });

  it("should handle whitespace around parameters", () => {
    const input = "  input: str  ,  output: int  ";
    const result = splitWithBracketPreservation(input);
    expect(result).toEqual(["input: str", "output: int"]);
  });

  it("should handle single parameter", () => {
    const input = "input: str";
    const result = splitWithBracketPreservation(input);
    expect(result).toEqual(["input: str"]);
  });

  it("should handle empty input", () => {
    const input = "";
    const result = splitWithBracketPreservation(input);
    expect(result).toEqual([]);
  });

  it("should handle complex nested type with multiple commas", () => {
    const input = "data: Dict[str, List[Tuple[int, float, str]]], other: str";
    const result = splitWithBracketPreservation(input);
    expect(result).toEqual([
      "data: Dict[str, List[Tuple[int, float, str]]]",
      "other: str",
    ]);
  });

  it("should handle multiple complex type annotations", () => {
    const input =
      "first: List[str], second: Dict[str, int], third: Tuple[float, List[str]]";
    const result = splitWithBracketPreservation(input);
    expect(result).toEqual([
      "first: List[str]",
      "second: Dict[str, int]",
      "third: Tuple[float, List[str]]",
    ]);
  });
});

describe("parsePythonMethodParameters", () => {
  it("should parse simple method with two parameters", () => {
    const code = `
def score(input: str, output: str):
    pass
`;
    const result = parsePythonMethodParameters(code, "score");
    expect(result).toEqual([
      { name: "input", type: "str", optional: false },
      { name: "output", type: "str", optional: false },
    ]);
  });

  it("should handle optional parameters with default values", () => {
    const code = `
def score(input: str, output: str = "default"):
    pass
`;
    const result = parsePythonMethodParameters(code, "score");
    expect(result).toEqual([
      { name: "input", type: "str", optional: false },
      { name: "output", type: "str", optional: true },
    ]);
  });

  it("should ignore variadic parameters", () => {
    const code = `
def score(input: str, output: str, *args, **kwargs):
    pass
`;
    const result = parsePythonMethodParameters(code, "score");
    expect(result).toEqual([
      { name: "input", type: "str", optional: false },
      { name: "output", type: "str", optional: false },
    ]);
  });

  it("should handle parameters without type annotations", () => {
    const code = `
def score(input, output):
    pass
`;
    const result = parsePythonMethodParameters(code, "score");
    expect(result).toEqual([
      { name: "input", type: "any", optional: false },
      { name: "output", type: "any", optional: false },
    ]);
  });

  it("should handle method with complex type annotations", () => {
    const code = `
def score(input: List[str], output: Dict[str, int] = {}):
    pass
`;
    const result = parsePythonMethodParameters(code, "score");
    expect(result).toEqual([
      { name: "input", type: "List[str]", optional: false },
      { name: "output", type: "Dict[str, int]", optional: true },
    ]);
  });

  it("should ignore self parameter in class methods", () => {
    const code = `
def score(self, input: str, output: str):
    pass
`;
    const result = parsePythonMethodParameters(code, "score");
    expect(result).toEqual([
      { name: "input", type: "str", optional: false },
      { name: "output", type: "str", optional: false },
    ]);
  });

  it("should throw an error when method is not found", () => {
    const code = `
def another_method(input: str):
    pass
`;
    expect(() => parsePythonMethodParameters(code, "score")).toThrowError(
      "Method score not found",
    );
  });

  it("should handle method with mixed parameter types", () => {
    const code = `
def score(input: str, output = None, count: int = 0):
    pass
`;
    const result = parsePythonMethodParameters(code, "score");
    expect(result).toEqual([
      { name: "input", type: "str", optional: false },
      { name: "output", type: "any", optional: true },
      { name: "count", type: "int", optional: true },
    ]);
  });

  it("should work with complex method signatures", () => {
    const code = `
def score(input: str, output: str, max_len: int = 100, **extra_kwargs):
    pass
`;
    const result = parsePythonMethodParameters(code, "score");
    expect(result).toEqual([
      { name: "input", type: "str", optional: false },
      { name: "output", type: "str", optional: false },
      { name: "max_len", type: "int", optional: true },
    ]);
  });
});
