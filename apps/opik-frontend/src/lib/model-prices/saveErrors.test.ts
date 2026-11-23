import { classifySaveError } from "./saveErrors";

describe("classifySaveError", () => {
  it("maps the statuses the replace endpoint documents", () => {
    expect(classifySaveError(403, {})).toEqual({ kind: "forbidden" });
    expect(classifySaveError(409, {})).toEqual({ kind: "conflict" });
    expect(classifySaveError(503, {})).toEqual({ kind: "disabled" });
  });

  it("keeps the problems of a 422, dropping entries that aren't problems", () => {
    const error = {
      row: 0,
      field: "input_price",
      code: "not_a_number",
      message: "input_price must be a number",
    };

    expect(
      classifySaveError(422, { errors: [error, "junk"], warnings: [] }),
    ).toEqual({ kind: "invalid", problems: { errors: [error], warnings: [] } });
  });

  it("treats a 422 without problems as unknown, keeping its message", () => {
    expect(classifySaveError(422, { errors: ["Unprocessable"] })).toEqual({
      kind: "unknown",
      message: "Unprocessable",
    });
  });

  it("reads the message from Opik and Comet error bodies", () => {
    expect(classifySaveError(500, { code: 500, message: "boom" })).toEqual({
      kind: "unknown",
      message: "boom",
    });
    expect(classifySaveError(400, { msg: "No API key" })).toEqual({
      kind: "unknown",
      message: "No API key",
    });
    expect(classifySaveError(undefined, undefined)).toEqual({
      kind: "unknown",
      message: undefined,
    });
  });

  it("cleans a server message before it reaches the toast", () => {
    const stack = `boom\n\tat Foo.bar(Foo.java:1)\u0000\u0085done`;

    expect(classifySaveError(500, { message: stack })).toEqual({
      kind: "unknown",
      message: "boom at Foo.bar(Foo.java:1) done",
    });
    expect(classifySaveError(500, { message: "\n\t " })).toEqual({
      kind: "unknown",
      message: undefined,
    });
  });

  it("strips bidi and other invisible format characters", () => {
    const disguised =
      "Save failed for \u202Etxt.exe\u202C \u2066model\u2069\u200B\uFEFF";

    expect(classifySaveError(500, { message: disguised })).toEqual({
      kind: "unknown",
      message: "Save failed for txt.exe model",
    });
    expect(classifySaveError(500, { message: "\u202E\u2066 " })).toEqual({
      kind: "unknown",
      message: undefined,
    });
  });

  it("keeps a long message whole, so request IDs and details survive", () => {
    const long = `Validation failed: ${"detail ".repeat(60)}request id abc-123`;

    const { message } = classifySaveError(500, { message: long }) as {
      message: string;
    };

    expect(message).toBe(long.replace(/\s+/g, " ").trim());
    expect(message.endsWith("request id abc-123")).toBe(true);
  });
});
