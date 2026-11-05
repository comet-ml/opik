import { describe, expect, it } from "vitest";

import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";
import { MAX_FIELD_NAME_LENGTH } from "@/v2/pages-shared/traces/AddToDatasetDialog/fieldMappingTypes";
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
