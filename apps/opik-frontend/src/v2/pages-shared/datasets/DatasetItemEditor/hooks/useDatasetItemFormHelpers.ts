import { z } from "zod";
import { FIELD_TYPE, DatasetField } from "./useDatasetItemData";
import { DatasetItemColumn } from "@/types/datasets";
import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";

export const getFieldType = (
  value: unknown,
): { type: FIELD_TYPE; isJsonString: boolean; value: unknown } => {
  if (value === null || value === undefined) {
    return {
      type: FIELD_TYPE.SIMPLE,
      isJsonString: false,
      value,
    };
  }

  const valueType = typeof value;

  if (valueType === "object") {
    return {
      type: FIELD_TYPE.COMPLEX,
      isJsonString: false,
      value,
    };
  }

  // Try to parse any string as JSON
  if (valueType === "string") {
    try {
      const parsed = JSON.parse(value as string);
      const parsedType = typeof parsed;

      // If parsed result is object or array, it's complex
      if (parsedType === "object" && parsed !== null) {
        return {
          type: FIELD_TYPE.COMPLEX,
          isJsonString: true,
          value: parsed,
        };
      }

      // If parsed result is a primitive (number, boolean, null), keep as simple but use parsed value
      return {
        type: FIELD_TYPE.SIMPLE,
        isJsonString: true,
        value: parsed,
      };
    } catch {
      // Not valid JSON, treat as regular string
      return {
        type: FIELD_TYPE.SIMPLE,
        isJsonString: false,
        value,
      };
    }
  }

  return {
    type: FIELD_TYPE.SIMPLE,
    isJsonString: false,
    value,
  };
};

export const createDynamicSchema = (fields: DatasetField[]) => {
  const schemaShape: Record<string, z.ZodTypeAny> = {};

  fields.forEach((field) => {
    if (field.type === FIELD_TYPE.COMPLEX) {
      schemaShape[field.key] = z.string().refine(
        (val) => {
          const trimmed = val.trim();
          if (trimmed === "") return true;
          try {
            const parsed = JSON.parse(trimmed);
            return typeof parsed === "object" && parsed !== null;
          } catch {
            return false;
          }
        },
        { message: "Must be a valid JSON object or array" },
      );
    } else {
      schemaShape[field.key] = z.any();
    }
  });

  return z.object(schemaShape);
};

const tryParseJsonStructure = (
  value: string,
): { parsed: unknown; isStructure: boolean } => {
  try {
    const parsed = JSON.parse(value);
    if (typeof parsed === "object" && parsed !== null) {
      return { parsed, isStructure: true };
    }
  } catch {
    // not valid JSON
  }
  return { parsed: undefined, isStructure: false };
};

const coerceToColumnType = (
  value: unknown,
  columnTypes: DYNAMIC_COLUMN_TYPE[],
): unknown => {
  if (typeof value !== "string") return value;

  const str = value as string;

  for (const colType of columnTypes) {
    if (colType === DYNAMIC_COLUMN_TYPE.string) continue;

    if (
      colType === DYNAMIC_COLUMN_TYPE.object ||
      colType === DYNAMIC_COLUMN_TYPE.array
    ) {
      const { parsed, isStructure } = tryParseJsonStructure(str);
      if (isStructure) return parsed;
    }

    if (
      colType === DYNAMIC_COLUMN_TYPE.number &&
      str.trim() !== "" &&
      !isNaN(Number(str))
    ) {
      return Number(str);
    }

    if (colType === DYNAMIC_COLUMN_TYPE.boolean) {
      const lower = str.toLowerCase();
      if (lower === "true") return true;
      if (lower === "false") return false;
    }
  }

  return str;
};

/**
 * Transforms form data before saving, using column type metadata to preserve
 * the correct JSON types in the API payload.
 *
 * Priority:
 * 1. If the user typed a valid JSON object/array, always use that (user intent).
 * 2. Try to coerce the value to the column's known non-string type (number, boolean).
 * 3. Fall back to string.
 */
export const prepareFormDataForSave = (
  formData: Record<string, unknown>,
  columns: DatasetItemColumn[],
): Record<string, unknown> => {
  const result: Record<string, unknown> = {};

  const columnTypeMap = new Map<string, DYNAMIC_COLUMN_TYPE[]>();
  columns.forEach((col) => {
    columnTypeMap.set(col.name, col.types);
  });

  for (const [key, value] of Object.entries(formData)) {
    // 1. If the user typed a valid JSON object/array, always use that
    if (typeof value === "string") {
      const { parsed, isStructure } = tryParseJsonStructure(value);
      if (isStructure) {
        result[key] = parsed;
        continue;
      }
    }

    // 2. Try to coerce based on column metadata
    const colTypes = columnTypeMap.get(key);
    if (colTypes && colTypes.length > 0) {
      result[key] = coerceToColumnType(value, colTypes);
      continue;
    }

    // 3. Fall back to value as-is
    result[key] = value;
  }

  return result;
};
