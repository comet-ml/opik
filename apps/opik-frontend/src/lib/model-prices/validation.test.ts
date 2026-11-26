import type { ModelPriceRow } from "./types";
import { EMPTY_DRAFT, modelPricesReducer } from "./draft";
import { validateDraft, validatePrice, validateRow } from "./validation";
import { canonicalPrice } from "./rows";

const codes = (row: ModelPriceRow) =>
  validateRow(row).map((problem) => `${problem.field}:${problem.code}`);

describe("canonicalPrice", () => {
  it.each([
    ["2.00", "2"],
    [" 007.50 ", "7.5"],
    [".5", "0.5"],
    ["0.000", "0"],
    ["-0", "0"],
    ["-1.50", "-1.5"],
    ["abc", "abc"],
    ["", ""],
  ])("turns %j into %j", (value, expected) => {
    expect(canonicalPrice(value)).toBe(expected);
  });
});

describe("validatePrice", () => {
  it.each([
    ["2.5", undefined],
    ["0", undefined],
    ["", undefined],
    ["100000", undefined],
    ["100000.0", undefined],
    ["0.0000000001", undefined],
    ["1e-6", "not_a_number"],
    ["2,5", "not_a_number"],
    ["-1", "negative_price"],
    ["0.00000000001", "too_many_decimals"],
    ["100000.0000000001", "price_too_high"],
    ["1000000", "price_too_high"],
  ])("checks input price %j", (value, code) => {
    expect(validatePrice("input_price", value)?.code).toBe(code);
  });

  it("rejects a zero cache price, since the backend would bill it at the input price", () => {
    expect(validatePrice("cache_read_price", "0")?.code).toBe(
      "cache_price_not_positive",
    );
    expect(validatePrice("cache_write_price", "0.00")?.code).toBe(
      "cache_price_not_positive",
    );
    expect(validatePrice("input_price", "0")).toBeUndefined();
  });
});

describe("validateRow", () => {
  it("accepts a fixed row with one price", () => {
    expect(
      codes({ model: "gpt-4o", kind: "fixed", output_price: "8" }),
    ).toEqual([]);
  });

  it("needs a model and at least one price", () => {
    expect(codes({ model: " ", kind: "fixed" })).toEqual([
      "model:missing_model",
      "null:no_price",
    ]);
  });

  it("needs an alias target that isn't the row itself", () => {
    expect(codes({ model: "a", kind: "alias" })).toEqual([
      "alias_of.model:missing_alias_target",
    ]);
    expect(
      codes({
        model: "GPT-4o",
        provider: "openai",
        kind: "alias",
        alias_of: { model: "gpt-4o", provider: "" },
      }),
    ).toEqual(["alias_of.model:alias_target_is_itself"]);
    expect(
      codes({
        model: "gpt-4o",
        provider: "azure",
        kind: "alias",
        alias_of: { model: "gpt-4o", provider: "openai" },
      }),
    ).toEqual([]);
  });

  it("ignores price fields on an alias row, since they aren't sent", () => {
    expect(
      codes({
        model: "a",
        kind: "alias",
        input_price: "oops",
        alias_of: { model: "b" },
      }),
    ).toEqual([]);
  });

  it("limits names and notes, and rejects control characters", () => {
    expect(
      codes({
        model: "x".repeat(256),
        provider: "open\u0007ai",
        kind: "fixed",
        input_price: "1",
        note: "n".repeat(501),
      }),
    ).toEqual([
      "model:name_too_long",
      "provider:control_characters",
      "note:note_too_long",
    ]);
  });
});

describe("validateDraft", () => {
  it("flags the second row that prices the same model on the same provider", () => {
    const draft = [
      {
        model: "GPT-4o",
        provider: "openai",
        kind: "fixed" as const,
        input_price: "1",
      },
      {
        model: "gpt-4o",
        provider: "OpenAI",
        kind: "fixed" as const,
        input_price: "2",
      },
      {
        model: "gpt-4o",
        provider: "",
        kind: "fixed" as const,
        input_price: "3",
      },
    ].reduce(
      (current, row) => modelPricesReducer(current, { type: "added", row }),
      EMPTY_DRAFT,
    );

    const problems = validateDraft(draft);

    expect(Object.keys(problems)).toEqual(["new-2"]);
    expect(problems["new-2"].map((problem) => problem.code)).toEqual([
      "duplicate_row",
    ]);
  });

  it("skips deleted rows", () => {
    const loaded = modelPricesReducer(EMPTY_DRAFT, {
      type: "loaded",
      list: { version: 1, rows: [{ id: "r", model: "", kind: "fixed" }] },
    });
    const deleted = modelPricesReducer(loaded, { type: "removed", key: "r" });

    expect(validateDraft(loaded)).toHaveProperty("r");
    expect(validateDraft(deleted)).toEqual({});
  });
});
