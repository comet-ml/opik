import uniqueId from "lodash/uniqueId";

import { DatasetItemColumn } from "@/types/datasets";
import { MAX_FIELD_NAME_LENGTH } from "@/constants/datasets";

export type DatasetFieldRow = {
  id: string;
  name: string;
  value: string;
  isJson: boolean;
  // What the record held when the row was loaded, so untouched fields are
  // saved as-is (null, numbers, booleans) instead of as the text shown.
  original?: { value: unknown; text: string };
};

export type DatasetFieldRowError =
  | "blank"
  | "duplicate"
  | "too_long"
  | "reserved";

// Assigning this key on a plain object sets its prototype instead of a field,
// so it would vanish from the saved data.
const RESERVED_FIELD_NAMES = new Set(["__proto__"]);

export const FIELD_ROW_ERROR_TEXT: Record<DatasetFieldRowError, string> = {
  blank: "Field name is required",
  duplicate: "Field name is already used",
  too_long: `Field name must be ${MAX_FIELD_NAME_LENGTH} characters or fewer`,
  reserved: "This field name is reserved",
};

export const createFieldRow = (name = "", value = ""): DatasetFieldRow => ({
  id: uniqueId("dataset-field-"),
  name,
  value,
  isJson: false,
});

const toFieldRow = (name: string, rawValue: unknown): DatasetFieldRow => {
  const isJson = typeof rawValue === "object" && rawValue !== null;
  const text =
    rawValue === null || rawValue === undefined
      ? ""
      : isJson
        ? JSON.stringify(rawValue, null, 2)
        : String(rawValue);

  return {
    ...createFieldRow(name, text),
    isJson,
    original: { value: rawValue, text },
  };
};

const toRawValue = (row: DatasetFieldRow): unknown => {
  const { original } = row;
  if (!original) return row.value;
  if (row.value === original.text) return original.value;

  const text = row.value.trim();
  if (
    typeof original.value === "number" &&
    text !== "" &&
    !isNaN(Number(text))
  ) {
    return Number(text);
  }
  if (
    typeof original.value === "boolean" &&
    (text === "true" || text === "false")
  ) {
    return text === "true";
  }
  return row.value;
};

export const columnsToFieldRows = (
  columns: DatasetItemColumn[],
): DatasetFieldRow[] => columns.map((column) => createFieldRow(column.name));

// Schema column order first so every record lists shared fields the same
// way; keys that only exist on this record follow in their own order.
export const dataToFieldRows = (
  data: Record<string, unknown>,
  columns: DatasetItemColumn[],
): DatasetFieldRow[] => {
  const columnNames = columns.map((column) => column.name);
  const keys = [
    ...columnNames.filter((name) => Object.hasOwn(data, name)),
    ...Object.keys(data).filter((key) => !columnNames.includes(key)),
  ];
  return keys.map((key) => toFieldRow(key, data[key]));
};

export const getFieldRowErrors = (
  rows: DatasetFieldRow[],
): Record<string, DatasetFieldRowError> => {
  const taken = new Set<string>();
  const errors: Record<string, DatasetFieldRowError> = {};

  rows.forEach((row) => {
    const name = row.name.trim();
    if (!name) {
      errors[row.id] = "blank";
      return;
    }
    if (name.length > MAX_FIELD_NAME_LENGTH) {
      errors[row.id] = "too_long";
      return;
    }
    if (RESERVED_FIELD_NAMES.has(name)) {
      errors[row.id] = "reserved";
      return;
    }
    if (taken.has(name)) {
      errors[row.id] = "duplicate";
      return;
    }
    taken.add(name);
  });

  return errors;
};

const isFilled = (row: DatasetFieldRow) => row.value.trim() !== "";

// Rows with an invalid name are skipped rather than blocking the save, so an
// autosaving editor keeps persisting the valid fields while one is mid-rename.
export const fieldRowsToData = (
  rows: DatasetFieldRow[],
): Record<string, unknown> => {
  const errors = getFieldRowErrors(rows);
  const data: Record<string, unknown> = {};

  rows.forEach((row) => {
    if (errors[row.id]) return;
    data[row.name.trim()] = toRawValue(row);
  });

  return data;
};

export const isInvalidJsonValue = (row: DatasetFieldRow) => {
  if (!row.isJson || !isFilled(row)) return false;
  try {
    const parsed = JSON.parse(row.value);
    return typeof parsed !== "object" || parsed === null;
  } catch {
    return true;
  }
};
