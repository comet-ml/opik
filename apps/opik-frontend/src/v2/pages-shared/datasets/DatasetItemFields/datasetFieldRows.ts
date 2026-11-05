import uniqueId from "lodash/uniqueId";

import { DatasetItemColumn } from "@/types/datasets";
import { MAX_FIELD_NAME_LENGTH } from "@/v2/pages-shared/traces/AddToDatasetDialog/fieldMappingTypes";

export type DatasetFieldRow = {
  id: string;
  name: string;
  value: string;
  isJson: boolean;
};

export type DatasetFieldRowError = "blank" | "duplicate" | "too_long";

export const FIELD_ROW_ERROR_TEXT: Record<DatasetFieldRowError, string> = {
  blank: "Field name is required",
  duplicate: "Field name is already used",
  too_long: `Field name must be ${MAX_FIELD_NAME_LENGTH} characters or fewer`,
};

export const createFieldRow = (name = "", value = ""): DatasetFieldRow => ({
  id: uniqueId("dataset-field-"),
  name,
  value,
  isJson: false,
});

const toFieldRow = (name: string, rawValue: unknown): DatasetFieldRow => {
  if (rawValue === null || rawValue === undefined) {
    return createFieldRow(name);
  }
  if (typeof rawValue === "object") {
    return {
      ...createFieldRow(name, JSON.stringify(rawValue, null, 2)),
      isJson: true,
    };
  }
  return createFieldRow(name, String(rawValue));
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
    ...columnNames.filter((name) => name in data),
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
    if (taken.has(name)) {
      errors[row.id] = "duplicate";
      return;
    }
    taken.add(name);
  });

  return errors;
};

const isFilled = (row: DatasetFieldRow) => row.value.trim() !== "";

export const countFilledFields = (
  rows: DatasetFieldRow[],
  errors: Record<string, DatasetFieldRowError>,
) => rows.filter((row) => !errors[row.id] && isFilled(row)).length;

// Rows with an invalid name are skipped rather than blocking the save, so an
// autosaving editor keeps persisting the valid fields while one is mid-rename.
export const fieldRowsToData = (
  rows: DatasetFieldRow[],
  { dropEmpty }: { dropEmpty: boolean },
): Record<string, string> => {
  const errors = getFieldRowErrors(rows);
  const data: Record<string, string> = {};

  rows.forEach((row) => {
    if (errors[row.id]) return;
    if (dropEmpty && !isFilled(row)) return;
    data[row.name.trim()] = row.value;
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
