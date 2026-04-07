import { castInputValue } from "@/runner/InProcessRunnerLoop";

describe("castInputValue", () => {
  // --- null / undefined passthrough ---

  it.each([null, undefined])("returns %s unchanged for any type", (value) => {
    expect(castInputValue(value, "boolean")).toBe(value);
    expect(castInputValue(value, "float")).toBe(value);
    expect(castInputValue(value, "string")).toBe(value);
  });

  // --- boolean type ---

  it.each([
    { input: "true", expected: true },
    { input: "True", expected: true },
    { input: "TRUE", expected: true },
    { input: "false", expected: false },
    { input: "False", expected: false },
    { input: "FALSE", expected: false },
    { input: "0", expected: false },
    { input: "1", expected: false },
  ])('boolean: string "$input" → $expected', ({ input, expected }) => {
    expect(castInputValue(input, "boolean")).toBe(expected);
  });

  it.each([
    { input: true, expected: true },
    { input: false, expected: false },
  ])("boolean: native boolean $input passes through", ({ input, expected }) => {
    expect(castInputValue(input, "boolean")).toBe(expected);
  });

  // --- float type ---

  it.each([
    { input: "42", expected: 42 },
    { input: "3.14", expected: 3.14 },
    { input: "-7", expected: -7 },
    { input: "0", expected: 0 },
  ])('float: string "$input" → $expected', ({ input, expected }) => {
    expect(castInputValue(input, "float")).toBe(expected);
  });

  it.each(["abc", "not-a-number"])(
    'float: non-numeric string "%s" → throws TypeError',
    (input) => {
      expect(() => castInputValue(input, "float")).toThrow(TypeError);
    }
  );

  it.each([
    { input: 42, expected: 42 },
    { input: 3.14, expected: 3.14 },
    { input: -7, expected: -7 },
    { input: 0, expected: 0 },
  ])("float: native number $input passes through", ({ input, expected }) => {
    expect(castInputValue(input, "float")).toBe(expected);
  });

  // --- string type ---

  it.each([
    { input: "hello", expected: "hello" },
    { input: "", expected: "" },
  ])('string: string "$input" passes through', ({ input, expected }) => {
    expect(castInputValue(input, "string")).toBe(expected);
  });

  it.each([
    { input: 42, expected: "42" },
    { input: 3.14, expected: "3.14" },
    { input: true, expected: "true" },
    { input: false, expected: "false" },
  ])("string: non-string primitive $input → $expected", ({ input, expected }) => {
    expect(castInputValue(input, "string")).toBe(expected);
  });

  it("string: object is JSON-serialised", () => {
    expect(castInputValue({ a: 1, b: "x" }, "string")).toBe('{"a":1,"b":"x"}');
  });

  it("string: array is JSON-serialised", () => {
    expect(castInputValue([1, "two", true], "string")).toBe('[1,"two",true]');
  });

  // --- unknown type falls back to string behaviour ---

  it("unknown type falls back to string behaviour", () => {
    expect(castInputValue("hello", "unknown_type")).toBe("hello");
    expect(castInputValue(42, "unknown_type")).toBe("42");
    expect(castInputValue({ x: 1 }, "unknown_type")).toBe('{"x":1}');
  });

  // --- multi-param combinations ---
  // Simulate a function with params (text: string, count: float, flag: boolean)
  // to verify that mixed-type inputs are each cast correctly.

  it.each([
    {
      label: "all values already typed natively",
      inputs: { text: "hi", count: 5, flag: true },
      params: [
        { name: "text", type: "string" },
        { name: "count", type: "float" },
        { name: "flag", type: "boolean" },
      ],
      expected: ["hi", 5, true],
    },
    {
      label: "all values serialised as strings (server-encoded)",
      inputs: { text: "hi", count: "5", flag: "true" },
      params: [
        { name: "text", type: "string" },
        { name: "count", type: "float" },
        { name: "flag", type: "boolean" },
      ],
      expected: ["hi", 5, true],
    },
    {
      label: "mixed: native number, string boolean",
      inputs: { text: "hello", count: 3, flag: "false" },
      params: [
        { name: "text", type: "string" },
        { name: "count", type: "float" },
        { name: "flag", type: "boolean" },
      ],
      expected: ["hello", 3, false],
    },
    {
      label: "object input serialised for string param",
      inputs: { text: "hi", payload: { key: "val" }, active: "true" },
      params: [
        { name: "text", type: "string" },
        { name: "payload", type: "string" },
        { name: "active", type: "boolean" },
      ],
      expected: ["hi", '{"key":"val"}', true],
    },
    {
      label: "number as string for both number and string params",
      inputs: { label: 99, score: "7.5" },
      params: [
        { name: "label", type: "string" },
        { name: "score", type: "float" },
      ],
      expected: ["99", 7.5],
    },
  ])("combination: $label", ({ inputs, params, expected }) => {
    const args = params.map((p) =>
      castInputValue((inputs as Record<string, unknown>)[p.name], p.type)
    );
    expect(args).toEqual(expected);
  });
});
