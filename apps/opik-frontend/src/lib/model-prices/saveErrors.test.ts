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
    const stack = `boom\n\tat Foo.bar(Foo.java:1)\u0000${"x".repeat(400)}`;

    const { message } = classifySaveError(500, { message: stack }) as {
      message: string;
    };

    expect(message.startsWith("boom at Foo.bar(Foo.java:1) xxx")).toBe(true);
    expect(message).toHaveLength(200);
    expect(message.endsWith("…")).toBe(true);
    expect(classifySaveError(500, { message: "\n\t " })).toEqual({
      kind: "unknown",
      message: undefined,
    });
  });
});
