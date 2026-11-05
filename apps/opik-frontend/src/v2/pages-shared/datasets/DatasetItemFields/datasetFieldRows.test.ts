import { describe, expect, it } from "vitest";

import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";
import { MAX_FIELD_NAME_LENGTH } from "@/constants/datasets";
import {
  countFilledFields,
  createFieldRow,
  dataToFieldRows,
  fieldRowsToData,
  getFieldRowErrors,
  isInvalidJsonValue,
} from "./datasetFieldRows";

const column = (name: string) => ({
  name,
  types: [DYNAMIC_COLUMN_TYPE.string],
});

describe("dataToFieldRows", () => {
  it("lists schema columns first, then keys only this record has", () => {
    const rows = dataToFieldRows({ extra: "x", output: "o", input: "i" }, [
      column("input"),
      column("output"),
      column("missing"),
    ]);

    expect(rows.map((r) => r.name)).toEqual(["input", "output", "extra"]);
  });

  it("pretty-prints objects and arrays and flags them as JSON", () => {
    const [obj, arr, num, nil] = dataToFieldRows(
      { obj: { a: 1 }, arr: [1, 2], num: 3, nil: null },
      [],
    );

    expect(obj).toMatchObject({ value: '{\n  "a": 1\n}', isJson: true });
    expect(arr).toMatchObject({ value: "[\n  1,\n  2\n]", isJson: true });
    expect(num).toMatchObject({ value: "3", isJson: false });
    expect(nil).toMatchObject({ value: "", isJson: false });
  });
});

describe("getFieldRowErrors", () => {
  it("flags blank, duplicate and too-long names", () => {
    const blank = createFieldRow("  ");
    const first = createFieldRow("input");
    const dup = createFieldRow(" input ");
    const long = createFieldRow("x".repeat(MAX_FIELD_NAME_LENGTH + 1));
    const ok = createFieldRow("x".repeat(MAX_FIELD_NAME_LENGTH));

    expect(getFieldRowErrors([blank, first, dup, long, ok])).toEqual({
      [blank.id]: "blank",
      [dup.id]: "duplicate",
      [long.id]: "too_long",
    });
  });
});

describe("fieldRowsToData", () => {
  const rows = [
    createFieldRow("input", "hello"),
    createFieldRow("empty", "  "),
    createFieldRow(""),
    createFieldRow("input", "duplicate"),
    createFieldRow(" padded ", "v"),
  ];

  it("skips rows with invalid names and trims names", () => {
    expect(fieldRowsToData(rows, { dropEmpty: false })).toEqual({
      input: "hello",
      empty: "  ",
      padded: "v",
    });
  });

  it("drops empty values when asked", () => {
    expect(fieldRowsToData(rows, { dropEmpty: true })).toEqual({
      input: "hello",
      padded: "v",
    });
  });
});

describe("countFilledFields", () => {
  it("counts only valid rows with a non-blank value", () => {
    const rows = [
      createFieldRow("a", "1"),
      createFieldRow("b", ""),
      createFieldRow("", "orphan"),
    ];

    expect(countFilledFields(rows, getFieldRowErrors(rows))).toBe(1);
  });
});

describe("isInvalidJsonValue", () => {
  const jsonRow = (value: string) => ({
    ...createFieldRow("f", value),
    isJson: true,
  });

  it("accepts objects, arrays and empty values", () => {
    expect(isInvalidJsonValue(jsonRow('{"a":1}'))).toBe(false);
    expect(isInvalidJsonValue(jsonRow("[]"))).toBe(false);
    expect(isInvalidJsonValue(jsonRow(""))).toBe(false);
  });

  it("rejects unparsable text and primitives", () => {
    expect(isInvalidJsonValue(jsonRow("{oops"))).toBe(true);
    expect(isInvalidJsonValue(jsonRow("42"))).toBe(true);
  });

  it("ignores rows that did not start as JSON", () => {
    expect(isInvalidJsonValue(createFieldRow("f", "{oops"))).toBe(false);
  });
});

describe("editing loaded records", () => {
  const load = (data: Record<string, unknown>) => dataToFieldRows(data, []);
  const rename = (rows: ReturnType<typeof load>, from: string, to: string) =>
    rows.map((r) => (r.name === from ? { ...r, name: to } : r));
  const setValue = (
    rows: ReturnType<typeof load>,
    name: string,
    value: string,
  ) => rows.map((r) => (r.name === name ? { ...r, value } : r));

  it("saves untouched fields with their original values and types", () => {
    const data = { a: null, n: 7, b: false, o: { x: 1 }, s: "text" };

    expect(fieldRowsToData(load(data), { dropEmpty: false })).toEqual(data);
  });

  it("keeps a number or boolean typed after the field is renamed", () => {
    const rows = rename(
      rename(load({ n: 7, b: true }), "n", "num"),
      "b",
      "flag",
    );

    expect(fieldRowsToData(rows, { dropEmpty: false })).toEqual({
      num: 7,
      flag: true,
    });
  });

  it("re-types an edited scalar when the new text still parses", () => {
    const rows = setValue(
      setValue(load({ n: 7, b: true }), "n", "8"),
      "b",
      "false",
    );

    expect(fieldRowsToData(rows, { dropEmpty: false })).toEqual({
      n: 8,
      b: false,
    });
  });

  it("falls back to text when an edited scalar no longer parses", () => {
    const rows = setValue(load({ n: 7 }), "n", "seven");

    expect(fieldRowsToData(rows, { dropEmpty: false })).toEqual({ n: "seven" });
  });

  it("only picks schema columns the record actually owns", () => {
    const rows = dataToFieldRows({ input: "i" }, [
      column("toString"),
      column("constructor"),
      column("input"),
    ]);

    expect(rows.map((r) => r.name)).toEqual(["input"]);
  });

  it("rejects the reserved __proto__ name", () => {
    const row = createFieldRow("__proto__", "x");

    expect(getFieldRowErrors([row])).toEqual({ [row.id]: "reserved" });
    expect(fieldRowsToData([row], { dropEmpty: true })).toEqual({});
  });
});
